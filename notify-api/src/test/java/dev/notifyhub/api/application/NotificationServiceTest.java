package dev.notifyhub.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import dev.notifyhub.api.messaging.NotificationPublisher;
import dev.notifyhub.api.messaging.PublishProperties;
import dev.notifyhub.api.messaging.PublishResult;
import dev.notifyhub.api.web.dto.CreateNotificationRequest;
import dev.notifyhub.api.web.dto.CreateNotificationResponse;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.persistence.LastError;
import dev.notifyhub.common.persistence.NotificationDocument;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;

/**
 * Criação: ramo de publish não confirmado (PUBLISH_FAILED condicional a PENDING, API-02, Armadilha 4) e
 * Idempotency-Key (API-03, D-05..D-07).
 */
class NotificationServiceTest {

    private static final CreateNotificationRequest REQUEST = new CreateNotificationRequest(
            Channel.EMAIL, "ana@exemplo.com", "welcome", Map.of("name", "Ana"));

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final PublishProperties props = new PublishProperties(Duration.ofSeconds(5), Duration.ofSeconds(5));
    private final NotificationService service = new NotificationService(mongo,
            new NotificationRepublisher(mongo, publisher, props), props);

    private void updateMatches(long modified) {
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(NotificationDocument.class)))
                .thenReturn(UpdateResult.acknowledged(modified, modified, null));
    }

    @Test
    void nackMarksPublishFailedConditionallyAndThrows() {
        when(publisher.publish(any(), any())).thenReturn(PublishResult.nacked("x"));
        updateMatches(1);

        assertThatThrownBy(() -> service.create(REQUEST, null))
                .isInstanceOfSatisfying(PublishFailedException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getHeaders().getFirst("Retry-After")).isEqualTo("5");
                });

        ArgumentCaptor<NotificationDocument> inserted = ArgumentCaptor.forClass(NotificationDocument.class);
        verify(mongo).insert(inserted.capture());
        String id = inserted.getValue().id();

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(query.capture(), update.capture(), eq(NotificationDocument.class));

        Document filter = query.getValue().getQueryObject();
        assertThat(filter.keySet()).containsExactlyInAnyOrder("_id", "status");
        assertThat(filter.get("_id")).isEqualTo(id);
        assertThat(String.valueOf(filter.get("status"))).isEqualTo("PENDING");

        Document set = (Document) update.getValue().getUpdateObject().get("$set");
        assertThat(set.get("status")).isEqualTo(NotificationStatus.PUBLISH_FAILED);
        assertThat(set.get("lastError")).isInstanceOfSatisfying(LastError.class, lastError -> {
            assertThat(lastError.code()).isEqualTo("PUBLISH_NACK");
            // T-02-01/T-03-21: mensagem fixa derivada do código, nunca o texto do nack
            assertThat(lastError.message()).isEqualTo("Publicação não confirmada pelo broker (PUBLISH_NACK)");
        });
        assertThat(set.get("updatedAt")).isInstanceOf(Instant.class);
        assertThat(update.getValue().getUpdateObject().keySet()).containsExactly("$set");
    }

    @Test
    void timeoutAfterWorkerAlreadyClaimedStillAnswers202() {
        when(publisher.publish(any(), any())).thenReturn(PublishResult.timeout());
        updateMatches(0);

        CreateNotificationResponse response = service.create(REQUEST, null).response();

        assertThat(response.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(response.id()).isNotBlank();
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(NotificationDocument.class));
    }

    @Test
    void confirmedPublishNeverUpdatesStatus() {
        when(publisher.publish(any(), any())).thenReturn(PublishResult.acked());

        CreateNotificationResponse response = service.create(REQUEST, null).response();

        assertThat(response.status()).isEqualTo(NotificationStatus.PENDING);
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), any(Class.class));
    }

    @Test
    void keyAndRequestHashAreStoredOnTheInsertedDocument() {
        when(publisher.publish(any(), any())).thenReturn(PublishResult.acked());

        NotificationService.CreateResult result = service.create(REQUEST, "k-1");

        ArgumentCaptor<NotificationDocument> inserted = ArgumentCaptor.forClass(NotificationDocument.class);
        verify(mongo).insert(inserted.capture());
        assertThat(inserted.getValue().idempotencyKey()).isEqualTo("k-1");
        assertThat(inserted.getValue().requestHash()).isEqualTo(RequestHasher.hash(REQUEST));
        assertThat(result.replayed()).isFalse();
        assertThat(result.response().id()).isEqualTo(inserted.getValue().id());
    }

    @Test
    void withoutKeyNeitherKeyNorHashIsStored() {
        when(publisher.publish(any(), any())).thenReturn(PublishResult.acked());

        service.create(REQUEST, null);

        ArgumentCaptor<NotificationDocument> inserted = ArgumentCaptor.forClass(NotificationDocument.class);
        verify(mongo).insert(inserted.capture());
        assertThat(inserted.getValue().idempotencyKey()).isNull();
        assertThat(inserted.getValue().requestHash()).isNull();
    }

    @Test
    void duplicateKeyWithTheSameHashReplaysTheExistingNotificationWithoutPublishing() {
        Instant createdAt = Instant.parse("2026-10-30T10:00:00Z");
        NotificationDocument existing = existingWithHash(RequestHasher.hash(REQUEST), NotificationStatus.SENT, createdAt);
        when(mongo.insert(any(NotificationDocument.class))).thenThrow(new DuplicateKeyException("E11000"));
        when(mongo.findOne(any(Query.class), eq(NotificationDocument.class))).thenReturn(existing);

        NotificationService.CreateResult result = service.create(REQUEST, "k-1");

        assertThat(result.replayed()).isTrue();
        assertThat(result.response().id()).isEqualTo(existing.id());
        assertThat(result.response().status()).isEqualTo(NotificationStatus.SENT);
        assertThat(result.response().createdAt()).isEqualTo(createdAt);
        verify(publisher, never()).publish(any(), any());

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findOne(query.capture(), eq(NotificationDocument.class));
        assertThat(query.getValue().getQueryObject()).isEqualTo(new Document("idempotencyKey", "k-1"));
    }

    @Test
    void duplicateKeyWithADifferentHashIs422WithoutEchoingKeyOrOriginalIdAndWithoutPublishing() {
        NotificationDocument existing = existingWithHash("outro", NotificationStatus.PENDING, Instant.now());
        when(mongo.insert(any(NotificationDocument.class))).thenThrow(new DuplicateKeyException("E11000"));
        when(mongo.findOne(any(Query.class), eq(NotificationDocument.class))).thenReturn(existing);

        assertThatThrownBy(() -> service.create(REQUEST, "k-1"))
                .isInstanceOfSatisfying(IdempotencyKeyReusedException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getBody().getType())
                            .hasToString("urn:notify-hub:problem:idempotency-key-reused");
                    assertThat(e.getBody().getDetail()).doesNotContain("k-1").doesNotContain(existing.id());
                    assertThat(e.getBody().getProperties()).isNull();
                });
        verify(publisher, never()).publish(any(), any());
    }

    @Test
    void duplicateKeyWhoseDocumentIsNotFoundByTheKeyIsRethrown() {
        DuplicateKeyException duplicate = new DuplicateKeyException("E11000 em outro índice");
        when(mongo.insert(any(NotificationDocument.class))).thenThrow(duplicate);
        when(mongo.findOne(any(Query.class), eq(NotificationDocument.class))).thenReturn(null);

        assertThatThrownBy(() -> service.create(REQUEST, "k-1")).isSameAs(duplicate);
        verify(publisher, never()).publish(any(), any());
    }

    @Test
    void duplicateKeyWithoutIdempotencyKeyIsRethrownWithoutLookingUp() {
        DuplicateKeyException duplicate = new DuplicateKeyException("E11000 _id");
        when(mongo.insert(any(NotificationDocument.class))).thenThrow(duplicate);

        assertThatThrownBy(() -> service.create(REQUEST, null)).isSameAs(duplicate);
        verify(mongo, never()).findOne(any(Query.class), any(Class.class));
    }

    private static NotificationDocument existingWithHash(String hash, NotificationStatus status, Instant createdAt) {
        return new NotificationDocument("11111111-2222-3333-4444-555555555555", Channel.EMAIL, "ana@exemplo.com",
                "welcome", Map.of("name", "Ana"), status, 0, 1, null, null, List.of(),
                createdAt, createdAt, null, null, null, "k-1", hash);
    }
}
