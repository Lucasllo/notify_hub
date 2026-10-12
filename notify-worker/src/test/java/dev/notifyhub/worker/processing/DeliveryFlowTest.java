package dev.notifyhub.worker.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.result.UpdateResult;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.worker.channel.ChannelAdapter;
import dev.notifyhub.worker.channel.SimulatedChannelAdapter;
import dev.notifyhub.worker.channel.SimulationProperties.ChannelSimulation;
import dev.notifyhub.worker.listener.ChannelListeners;
import dev.notifyhub.worker.template.TemplateCatalog;
import dev.notifyhub.worker.template.TemplateDocument;
import dev.notifyhub.worker.template.TemplateRenderer;
import dev.notifyhub.worker.template.TemplateSeeder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/**
 * Caminho inteiro do worker sem broker nem banco: listener EMAIL → claim → template do seed →
 * renderização → adapter simulado → SENT numa única atualização com a tentativa registrada.
 */
class DeliveryFlowTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private ChannelListeners listeners;

    @BeforeEach
    void wireRealWorker() {
        TemplateDocument welcome = new TemplateSeeder(mongo, new ObjectMapper()).loadSeed().stream()
                .filter(t -> t.id().equals("welcome"))
                .findFirst()
                .orElseThrow();
        when(mongo.findById("welcome", TemplateDocument.class)).thenReturn(welcome);

        NotificationDocument processing = new NotificationDocument(
                "n-1", Channel.EMAIL, "ana@exemplo.com", "welcome", Map.of("name", "Ana"),
                NotificationStatus.PROCESSING, 0, 0, null, null, List.of(),
                NOW, NOW, NOW, NOW.plus(Duration.ofMinutes(5)), null);
        when(mongo.findAndModify(any(Query.class), any(UpdateDefinition.class),
                any(FindAndModifyOptions.class), eq(NotificationDocument.class)))
                .thenReturn(processing);
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        ChannelSimulation instant = new ChannelSimulation(Duration.ZERO, Duration.ZERO, 0.0);
        List<ChannelAdapter> adapters = List.of(
                new SimulatedChannelAdapter(Channel.EMAIL, instant, ThreadLocalRandom::current),
                new SimulatedChannelAdapter(Channel.SMS, instant, ThreadLocalRandom::current),
                new SimulatedChannelAdapter(Channel.WEBHOOK, instant, ThreadLocalRandom::current));

        NotificationStateService state = new NotificationStateService(mongo, Duration.ofMinutes(5));
        DeliveryProcessor processor = new DeliveryProcessor(
                state, new TemplateCatalog(mongo), new TemplateRenderer(), adapters);
        listeners = new ChannelListeners(processor);
    }

    @Test
    void emailMessageEndsSentInASingleAtomicUpdateWithTheAttemptRecorded() {
        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null);

        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongo).updateFirst(any(Query.class), update.capture(), eq(NotificationDocument.class));
        Document bson = MongoBson.update(update.getValue());

        Document set = bson.get("$set", Document.class);
        assertThat(set.get("status")).isEqualTo("SENT");
        assertThat(set.get("rendered", Document.class).getString("subject")).contains("Ana");
        assertThat(bson.get("$inc", Document.class).get("attemptCount")).isEqualTo(1);

        Document push = bson.get("$push", Document.class).get("attempts", Document.class);
        assertThat(push.get("$slice")).isEqualTo(-50);
        List<?> each = push.get("$each", List.class);
        assertThat(each).hasSize(1);
        Document attempt = (Document) each.get(0);
        assertThat(attempt.get("number")).isEqualTo(1);
        assertThat(attempt.get("retryLevel")).isEqualTo(0);
        assertThat(attempt.get("outcome")).isEqualTo("SUCCESS");
        assertThat(attempt.getString("thread")).isNotBlank();
    }

    @Test
    void claimFiltersByIdAndAbsentRetryHeaderBecomesLevelZero() {
        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findAndModify(query.capture(), any(UpdateDefinition.class),
                any(FindAndModifyOptions.class), eq(NotificationDocument.class));
        Document filter = MongoBson.query(query.getValue());

        assertThat(filter.get("_id")).isEqualTo("n-1");
        assertThat(filter.get("retryCount", Document.class).get("$lte")).isEqualTo(0);
    }
}
