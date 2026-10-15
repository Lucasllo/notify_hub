package dev.notifyhub.worker.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.result.UpdateResult;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.messaging.TopologyProperties;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.worker.channel.ChannelAdapter;
import dev.notifyhub.worker.channel.SimulatedChannelAdapter;
import dev.notifyhub.worker.channel.SimulationProperties.ChannelSimulation;
import dev.notifyhub.worker.failure.FailureClassifier;
import dev.notifyhub.worker.listener.ChannelListeners;
import dev.notifyhub.worker.messaging.DeliveryRoutingPublisher;
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
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/**
 * Caminho inteiro do worker sem broker nem banco: listener EMAIL → claim → template do seed →
 * renderização → adapter simulado → resultado numa única atualização com a tentativa registrada.
 * Na falha permanente, o resultado é FAILED e uma cópia confirmada vai para a DLQ do canal. Na
 * falha transitória, o resultado é RETRYING e um token confirmado desce para a fila de delay do
 * próximo nível (D-11).
 */
class DeliveryFlowTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final TopologyProperties TOPOLOGY = new TopologyProperties(List.of(
            Duration.ofSeconds(5), Duration.ofSeconds(25), Duration.ofSeconds(125)));

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private ChannelAdapter emailAdapter;
    private ChannelListeners listeners;

    @BeforeEach
    void wireRealWorker() {
        TemplateDocument welcome = new TemplateSeeder(mongo, new ObjectMapper()).loadSeed().stream()
                .filter(t -> t.id().equals("welcome"))
                .findFirst()
                .orElseThrow();
        when(mongo.findById("welcome", TemplateDocument.class)).thenReturn(welcome);

        claimReturns(processingDoc("welcome", 0));
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        // o broker confirma toda publicação
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(4);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        listeners = listenersWithEmailFailureRate(0.0);
    }

    /** Worker real com o adapter EMAIL falhando na taxa dada (1.0 = sempre transitória). */
    private ChannelListeners listenersWithEmailFailureRate(double emailFailureRate) {
        ChannelSimulation instant = new ChannelSimulation(Duration.ZERO, Duration.ZERO, 0.0);
        ChannelSimulation email = new ChannelSimulation(Duration.ZERO, Duration.ZERO, emailFailureRate);
        emailAdapter = spy(new SimulatedChannelAdapter(Channel.EMAIL, email, ThreadLocalRandom::current));
        List<ChannelAdapter> adapters = List.of(
                emailAdapter,
                new SimulatedChannelAdapter(Channel.SMS, instant, ThreadLocalRandom::current),
                new SimulatedChannelAdapter(Channel.WEBHOOK, instant, ThreadLocalRandom::current));

        NotificationStateService state = new NotificationStateService(mongo, Duration.ofMinutes(5));
        DeliveryProcessor processor = new DeliveryProcessor(
                state, new TemplateCatalog(mongo), new TemplateRenderer(), adapters,
                new FailureClassifier(), new DeliveryRoutingPublisher(rabbit, Duration.ofSeconds(2)), TOPOLOGY);
        return new ChannelListeners(processor);
    }

    private static NotificationDocument processingDoc(String templateCode, int retryCount) {
        return processingDoc(templateCode, retryCount, 0);
    }

    private static NotificationDocument processingDoc(String templateCode, int retryCount, int attemptCount) {
        return new NotificationDocument(
                "n-1", Channel.EMAIL, "ana@exemplo.com", templateCode, Map.of("name", "Ana"),
                NotificationStatus.PROCESSING, retryCount, attemptCount, null, null, List.of(),
                NOW, NOW, NOW, NOW.plus(Duration.ofMinutes(5)), null);
    }

    private void claimReturns(NotificationDocument doc) {
        when(mongo.findAndModify(any(Query.class), any(UpdateDefinition.class),
                any(FindAndModifyOptions.class), eq(NotificationDocument.class)))
                .thenReturn(doc);
    }

    private record Result(Query query, UpdateDefinition update) {
    }

    private Result captureResult() {
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongo).updateFirst(query.capture(), update.capture(), eq(NotificationDocument.class));
        return new Result(query.getValue(), update.getValue());
    }

    private record Published(Object body, MessagePostProcessor postProcessor, CorrelationData correlation) {
    }

    private Published captureDlqPublish(String routingKey) {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> mpp = ArgumentCaptor.forClass(MessagePostProcessor.class);
        ArgumentCaptor<CorrelationData> cd = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbit).convertAndSend(eq("notify.dlx"), eq(routingKey), body.capture(), mpp.capture(), cd.capture());
        return new Published(body.getValue(), mpp.getValue(), cd.getValue());
    }

    private static MessageProperties applied(MessagePostProcessor mpp) {
        return mpp.postProcessMessage(new Message(new byte[0], new MessageProperties())).getMessageProperties();
    }

    // ------------------------------------------------------------ caminho feliz

    @Test
    void emailMessageEndsSentInASingleAtomicUpdateWithTheAttemptRecorded() {
        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null);

        Document bson = MongoBson.update(captureResult().update());

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

    // ------------------------------------------------------------ falha permanente

    @Test
    void missingTemplateEndsWithoutExceptionAndNeverCallsTheAdapter() {
        claimReturns(processingDoc("nao-existe", 0));
        assertThat(mongo.findById("nao-existe", TemplateDocument.class)).isNull();

        assertThatCode(() -> listeners.onEmail(
                new NotificationMessage("n-1", Channel.EMAIL, "nao-existe", NOW), null))
                .doesNotThrowAnyException();

        verify(emailAdapter, never()).send(anyString(), any());
    }

    @Test
    void missingTemplateIsWrittenAsFailedInOneFencedUpdateWithTheAttempt() {
        claimReturns(processingDoc("nao-existe", 0));

        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "nao-existe", NOW), null);

        Result result = captureResult();
        assertThat(MongoBson.query(result.query())).isEqualTo(new Document("_id", "n-1")
                .append("status", "PROCESSING")
                .append("retryCount", 0));

        Document bson = MongoBson.update(result.update());
        Document set = bson.get("$set", Document.class);
        assertThat(set.get("status")).isEqualTo("FAILED");
        Document lastError = set.get("lastError", Document.class);
        assertThat(lastError.getString("code")).isEqualTo("TEMPLATE_NOT_FOUND");
        assertThat(lastError.get("failureClass")).isEqualTo("PERMANENT");
        assertThat(bson.get("$unset", Document.class).keySet()).contains("leaseUntil");
        assertThat(bson.get("$inc", Document.class).get("attemptCount")).isEqualTo(1);

        Document push = bson.get("$push", Document.class).get("attempts", Document.class);
        assertThat(push.get("$slice")).isEqualTo(-50);
        List<?> each = push.get("$each", List.class);
        assertThat(each).hasSize(1);
        Document attempt = (Document) each.get(0);
        assertThat(attempt.get("number")).isEqualTo(1);
        assertThat(attempt.get("retryLevel")).isEqualTo(0);
        assertThat(attempt.get("outcome")).isEqualTo("PERMANENT_FAILURE");
        assertThat(attempt.get("errorCode")).isEqualTo("TEMPLATE_NOT_FOUND");
        assertThat(attempt.get("failureClass")).isEqualTo("PERMANENT");
        assertThat(((Number) attempt.get("durationMs")).longValue()).isZero();
    }

    @Test
    void missingTemplateSendsAConfirmedCopyToTheChannelDlqWithTheFailureClass() {
        claimReturns(processingDoc("nao-existe", 0));
        NotificationMessage message = new NotificationMessage("n-1", Channel.EMAIL, "nao-existe", NOW);

        listeners.onEmail(message, null);

        Published published = captureDlqPublish("email.dlq");
        assertThat(published.body()).isEqualTo(message);
        MessageProperties props = applied(published.postProcessor());
        assertThat(props.getMessageId()).isEqualTo("n-1");
        assertThat((Object) props.getHeader("x-failure-class")).isEqualTo("PERMANENT");
        assertThat((Object) props.getHeader("notify-retry-count")).isEqualTo(0);
        assertThat(published.correlation().getId()).isNotEqualTo("n-1");
    }

    @Test
    void retryLevelFromTheHeaderIsRecordedInTheAttemptAndCarriedToTheDlqCopy() {
        claimReturns(processingDoc("nao-existe", 2));

        listeners.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "nao-existe", NOW), "2");

        Document bson = MongoBson.update(captureResult().update());
        Document attempt = (Document) bson.get("$push", Document.class)
                .get("attempts", Document.class).get("$each", List.class).get(0);
        assertThat(attempt.get("retryLevel")).isEqualTo(2);

        MessageProperties props = applied(captureDlqPublish("email.dlq").postProcessor());
        assertThat((Object) props.getHeader("notify-retry-count")).isEqualTo(2);
    }

    // ------------------------------------------------------------ falha transitória (D-11)

    private Published captureRetryPublish(String routingKey) {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> mpp = ArgumentCaptor.forClass(MessagePostProcessor.class);
        ArgumentCaptor<CorrelationData> cd = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbit).convertAndSend(eq("notify.retry"), eq(routingKey), body.capture(), mpp.capture(), cd.capture());
        return new Published(body.getValue(), mpp.getValue(), cd.getValue());
    }

    @Test
    void transientFailureAtLevelZeroIsWrittenAsRetryingWithoutTouchingRetryCount() {
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);

        failing.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), null);

        Result result = captureResult();
        assertThat(MongoBson.query(result.query())).isEqualTo(new Document("_id", "n-1")
                .append("status", "PROCESSING")
                .append("retryCount", 0));

        Document bson = MongoBson.update(result.update());
        Document set = bson.get("$set", Document.class);
        assertThat(set.keySet()).containsExactlyInAnyOrder("status", "lastError", "updatedAt");
        assertThat(set.get("status")).isEqualTo("RETRYING");
        Document lastError = set.get("lastError", Document.class);
        assertThat(lastError.getString("code")).isEqualTo("SIMULATED_TRANSIENT");
        assertThat(lastError.get("failureClass")).isEqualTo("TRANSIENT");
        assertThat(bson.get("$unset", Document.class).keySet()).containsExactly("leaseUntil");
        assertThat(bson.get("$inc", Document.class).get("attemptCount")).isEqualTo(1);

        List<?> each = bson.get("$push", Document.class).get("attempts", Document.class).get("$each", List.class);
        assertThat(each).hasSize(1);
        Document attempt = (Document) each.get(0);
        assertThat(attempt.get("outcome")).isEqualTo("TRANSIENT_FAILURE");
        assertThat(attempt.get("errorCode")).isEqualTo("SIMULATED_TRANSIENT");
        assertThat(attempt.get("retryLevel")).isEqualTo(0);
    }

    @Test
    void transientFailurePublishesAConfirmedTokenToTheFirstDelayLevelAfterTheRetryingUpdate() {
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);
        NotificationMessage message = new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW);

        assertThatCode(() -> failing.onEmail(message, null)).doesNotThrowAnyException();

        Published published = captureRetryPublish("email.retry.5s");
        assertThat(published.body()).isEqualTo(message);
        MessageProperties props = applied(published.postProcessor());
        assertThat(props.getMessageId()).isEqualTo("n-1");
        assertThat((Object) props.getHeader("notify-retry-count")).isEqualTo(1);
        assertThat(published.correlation().getId()).isNotEqualTo("n-1");

        InOrder order = inOrder(mongo, rabbit);
        order.verify(mongo).updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class));
        order.verify(rabbit).convertAndSend(eq("notify.retry"), eq("email.retry.5s"), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        verify(rabbit, never()).convertAndSend(eq("notify.dlx"), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void transientFailureAtLevelOneGoesToTheSecondDelayLevel() {
        claimReturns(processingDoc("welcome", 1));
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);

        failing.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), "1");

        MessageProperties props = applied(captureRetryPublish("email.retry.25s").postProcessor());
        assertThat((Object) props.getHeader("notify-retry-count")).isEqualTo(2);
    }

    // ------------------------------------------------------------ escada esgotada (DLV-05)

    @Test
    void transientFailureOnTheLastLevelEndsDeadLetteredWithATransientDlqCopy() {
        claimReturns(processingDoc("welcome", 3, 3));
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);

        assertThatCode(() -> failing.onEmail(new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW), "3"))
                .doesNotThrowAnyException();

        Result result = captureResult();
        assertThat(MongoBson.query(result.query())).isEqualTo(new Document("_id", "n-1")
                .append("status", "PROCESSING")
                .append("retryCount", 3));
        Document set = MongoBson.update(result.update()).get("$set", Document.class);
        assertThat(set.get("status")).isEqualTo("DEAD_LETTERED");
        assertThat(set.get("lastError", Document.class).get("failureClass")).isEqualTo("TRANSIENT");

        MessageProperties props = applied(captureDlqPublish("email.dlq").postProcessor());
        assertThat((Object) props.getHeader("x-failure-class")).isEqualTo("TRANSIENT");
        assertThat((Object) props.getHeader("notify-retry-count")).isEqualTo(3);
        verify(rabbit, never()).convertAndSend(eq("notify.retry"), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void alwaysTransientWalksTheWholeLadderAndEndsDeadLetteredOnTheFourthAttempt() {
        ChannelListeners failing = listenersWithEmailFailureRate(1.0);
        NotificationMessage message = new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW);

        for (int h = 0; h <= 3; h++) {
            claimReturns(processingDoc("welcome", h, h));
            failing.onEmail(message, h == 0 ? null : String.valueOf(h));
        }

        ArgumentCaptor<UpdateDefinition> updates = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongo, times(4)).updateFirst(any(Query.class), updates.capture(), eq(NotificationDocument.class));
        List<Document> bson = updates.getAllValues().stream().map(MongoBson::update).toList();
        assertThat(bson).extracting(b -> b.get("$set", Document.class).get("status"))
                .containsExactly("RETRYING", "RETRYING", "RETRYING", "DEAD_LETTERED");
        assertThat(bson).extracting(b -> ((Document) b.get("$push", Document.class).get("attempts", Document.class)
                        .get("$each", List.class).get(0)).get("number"))
                .containsExactly(1, 2, 3, 4);

        InOrder order = inOrder(rabbit);
        for (String key : List.of("email.retry.5s", "email.retry.25s", "email.retry.125s")) {
            order.verify(rabbit).convertAndSend(eq("notify.retry"), eq(key), any(Object.class),
                    any(MessagePostProcessor.class), any(CorrelationData.class));
        }
        order.verify(rabbit).convertAndSend(eq("notify.dlx"), eq("email.dlq"), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }
}
