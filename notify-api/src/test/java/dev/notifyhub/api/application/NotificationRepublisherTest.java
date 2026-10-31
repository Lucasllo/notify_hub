package dev.notifyhub.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import dev.notifyhub.api.application.NotificationRepublisher.RepublishOutcome;
import dev.notifyhub.api.messaging.NotificationPublisher;
import dev.notifyhub.api.messaging.PublishProperties;
import dev.notifyhub.api.messaging.PublishResult;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.persistence.LastError;
import dev.notifyhub.common.persistence.NotificationDocument;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Serviço único de republish (ADM-03, D-08, Padrão 4b): fence PUBLISH_FAILED → PENDING antes do
 * publish, nada publicado quando o fence se perde (T-03-22), {@code lastError} fixo na falha (T-03-21)
 * e lote que para na primeira falha com 503 parcial. Sem Docker.
 */
class NotificationRepublisherTest {

    private static final Instant CREATED = Instant.parse("2026-10-30T10:00:00Z");

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final NotificationRepublisher republisher = new NotificationRepublisher(mongo, publisher,
            new PublishProperties(Duration.ofSeconds(5), Duration.ofSeconds(5)));

    private static NotificationDocument publishFailed(String id) {
        return new NotificationDocument(id, Channel.EMAIL, "ana@exemplo.com", "welcome", Map.of("name", "Ana"),
                NotificationStatus.PUBLISH_FAILED, 0, 0, null, null, List.of(), CREATED, CREATED, null, null, null);
    }

    private void updateMatches(long modified) {
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(NotificationDocument.class)))
                .thenReturn(UpdateResult.acknowledged(modified, modified, null));
    }

    private static UpdateResult matched(long modified) {
        return UpdateResult.acknowledged(modified, modified, null);
    }

    // ---------------------------------------------------------------- republish

    @Test
    void republishFencesPublishFailedToPendingThenPublishes() {
        updateMatches(1);
        when(publisher.publish(any(), any())).thenReturn(PublishResult.acked());

        RepublishOutcome outcome = republisher.republish(publishFailed("n-1"));

        assertThat(outcome.kind()).isEqualTo(RepublishOutcome.Kind.REPUBLISHED);
        assertThat(outcome.failure()).isNull();

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(query.capture(), update.capture(), eq(NotificationDocument.class));
        Document filter = query.getValue().getQueryObject();
        assertThat(filter.keySet()).containsExactlyInAnyOrder("_id", "status");
        assertThat(filter.get("_id")).isEqualTo("n-1");
        assertThat(String.valueOf(filter.get("status"))).isEqualTo("PUBLISH_FAILED");
        assertThat(update.getValue().getUpdateObject().keySet()).containsExactly("$set");
        Document set = (Document) update.getValue().getUpdateObject().get("$set");
        assertThat(set.keySet()).containsExactlyInAnyOrder("status", "updatedAt");
        assertThat(set.get("status")).isEqualTo(NotificationStatus.PENDING);
        assertThat(set.get("updatedAt")).isInstanceOf(Instant.class);

        ArgumentCaptor<NotificationMessage> message = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(publisher).publish(message.capture(), eq(Duration.ofSeconds(5)));
        assertThat(message.getValue().notificationId()).isEqualTo("n-1");
    }

    @Test
    void lostFenceDoesNotPublish() {
        updateMatches(0);

        RepublishOutcome outcome = republisher.republish(publishFailed("n-1"));

        assertThat(outcome.kind()).isEqualTo(RepublishOutcome.Kind.NOT_PUBLISH_FAILED);
        verify(publisher, never()).publish(any(), any());
    }

    @Test
    void returnedPublishMarksPublishFailedAgainWithTheFixedMessage() {
        updateMatches(1);
        when(publisher.publish(any(), any())).thenReturn(PublishResult.unroutable("NO_ROUTE"));

        RepublishOutcome outcome = republisher.republish(publishFailed("n-1"));

        assertThat(outcome.kind()).isEqualTo(RepublishOutcome.Kind.FAILED);
        assertThat(outcome.failure().errorCode()).isEqualTo("PUBLISH_RETURNED");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo, times(2)).updateFirst(query.capture(), update.capture(), eq(NotificationDocument.class));
        Document secondFilter = query.getAllValues().get(1).getQueryObject();
        assertThat(String.valueOf(secondFilter.get("status"))).isEqualTo("PENDING");
        Document set = (Document) update.getAllValues().get(1).getUpdateObject().get("$set");
        assertThat(set.get("status")).isEqualTo(NotificationStatus.PUBLISH_FAILED);
        assertThat(set.get("lastError")).isInstanceOfSatisfying(LastError.class, lastError -> {
            assertThat(lastError.code()).isEqualTo("PUBLISH_RETURNED");
            assertThat(lastError.message()).isEqualTo("Publicação não confirmada pelo broker (PUBLISH_RETURNED)");
        });
    }

    // ---------------------------------------------------------------- lote

    @Test
    void batchSearchesPublishFailedOldestFirstAndStopsAtTheFirstFailureWith503Partial() {
        when(mongo.find(any(Query.class), eq(NotificationDocument.class)))
                .thenReturn(List.of(publishFailed("n-1"), publishFailed("n-2"), publishFailed("n-3")));
        updateMatches(1);
        when(publisher.publish(any(), any()))
                .thenReturn(PublishResult.acked())
                .thenReturn(PublishResult.unroutable("NO_ROUTE"));

        assertThatThrownBy(() -> republisher.republishFailed(10))
                .isInstanceOfSatisfying(RepublishInterruptedException.class, e -> {
                    assertThat(e.getReport().requested()).isEqualTo(10);
                    assertThat(e.getReport().republished()).isEqualTo(1);
                    assertThat(e.getReason()).isEqualTo("PUBLISH_RETURNED");
                });

        ArgumentCaptor<Query> search = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(search.capture(), eq(NotificationDocument.class));
        Document filter = search.getValue().getQueryObject();
        assertThat(filter.keySet()).containsExactly("status");
        assertThat(String.valueOf(filter.get("status"))).isEqualTo("PUBLISH_FAILED");
        assertThat(search.getValue().getSortObject()).isEqualTo(new Document("createdAt", 1).append("_id", 1));
        assertThat(search.getValue().getLimit()).isEqualTo(10);

        // o 3º nunca foi tentado
        verify(publisher, times(2)).publish(any(), any());
        ArgumentCaptor<Query> updates = ArgumentCaptor.forClass(Query.class);
        verify(mongo, times(3)).updateFirst(updates.capture(), any(Update.class), eq(NotificationDocument.class));
        assertThat(updates.getAllValues()).noneMatch(q -> "n-3".equals(q.getQueryObject().get("_id")));
    }

    @Test
    void batchCountsOnlyRepublishedAndSkipsLostFences() {
        when(mongo.find(any(Query.class), eq(NotificationDocument.class)))
                .thenReturn(List.of(publishFailed("n-1"), publishFailed("n-2")));
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(NotificationDocument.class)))
                .thenReturn(matched(0))
                .thenReturn(matched(1));
        when(publisher.publish(any(), any())).thenReturn(PublishResult.acked());

        NotificationRepublisher.RepublishReport report = republisher.republishFailed(5);

        assertThat(report.requested()).isEqualTo(5);
        assertThat(report.republished()).isEqualTo(1);
        verify(publisher, times(1)).publish(any(), any());
    }

    @Test
    void mongoDownOnTheSearchIs503WithMongoUnavailable() {
        when(mongo.find(any(Query.class), eq(NotificationDocument.class)))
                .thenThrow(new DataAccessResourceFailureException("conexão recusada"));

        assertThatThrownBy(() -> republisher.republishFailed(10))
                .isInstanceOfSatisfying(RepublishInterruptedException.class, e -> {
                    assertThat(e.getReport().requested()).isEqualTo(10);
                    assertThat(e.getReport().republished()).isZero();
                    assertThat(e.getReason()).isEqualTo(NotificationRepublisher.MONGO_UNAVAILABLE);
                });
        verify(publisher, never()).publish(any(), any());
    }

    @Test
    void mongoDownOnAFenceUpdateIs503WithTheAccumulatedCounts() {
        when(mongo.find(any(Query.class), eq(NotificationDocument.class)))
                .thenReturn(List.of(publishFailed("n-1"), publishFailed("n-2")));
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(NotificationDocument.class)))
                .thenReturn(matched(1))
                .thenThrow(new DataAccessResourceFailureException("conexão caiu"));
        when(publisher.publish(any(), any())).thenReturn(PublishResult.acked());

        assertThatThrownBy(() -> republisher.republishFailed(10))
                .isInstanceOfSatisfying(RepublishInterruptedException.class, e -> {
                    assertThat(e.getReport().republished()).isEqualTo(1);
                    assertThat(e.getReason()).isEqualTo(NotificationRepublisher.MONGO_UNAVAILABLE);
                    // T-02-01: o corpo nunca carrega a mensagem da exceção
                    assertThat(e.getBody().getDetail()).doesNotContain("conexão");
                });
    }

    @Test
    void interruptedProblemCarriesPartialCountsAndTheClosedReason() {
        RepublishInterruptedException e = new RepublishInterruptedException(
                new NotificationRepublisher.RepublishReport(10, 1), "PUBLISH_TIMEOUT");

        assertThat(e.getStatusCode().value()).isEqualTo(503);
        assertThat(e.getBody().getType()).hasToString("urn:notify-hub:problem:republish-interrupted");
        assertThat(e.getBody().getTitle()).isEqualTo("Republicação interrompida");
        assertThat(e.getBody().getDetail()).contains("PUBLISH_TIMEOUT").contains("PUBLISH_FAILED");
        assertThat(e.getBody().getProperties())
                .containsEntry("requested", 10)
                .containsEntry("republished", 1)
                .containsEntry("reason", "PUBLISH_TIMEOUT");
    }
}
