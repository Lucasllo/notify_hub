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
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;

/** Ramo de publish não confirmado: PUBLISH_FAILED condicional a PENDING (API-02, Armadilha 4). */
class NotificationServiceTest {

    private static final CreateNotificationRequest REQUEST = new CreateNotificationRequest(
            Channel.EMAIL, "ana@exemplo.com", "welcome", Map.of("name", "Ana"));

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final NotificationService service = new NotificationService(mongo, publisher,
            new PublishProperties(Duration.ofSeconds(5), Duration.ofSeconds(5)));

    private void updateMatches(long modified) {
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(NotificationDocument.class)))
                .thenReturn(UpdateResult.acknowledged(modified, modified, null));
    }

    @Test
    void nackMarksPublishFailedConditionallyAndThrows() {
        when(publisher.publish(any(), any())).thenReturn(PublishResult.nacked("x"));
        updateMatches(1);

        assertThatThrownBy(() -> service.create(REQUEST))
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
            assertThat(lastError.message()).isEqualTo("x");
        });
        assertThat(set.get("updatedAt")).isInstanceOf(Instant.class);
        assertThat(update.getValue().getUpdateObject().keySet()).containsExactly("$set");
    }

    @Test
    void timeoutAfterWorkerAlreadyClaimedStillAnswers202() {
        when(publisher.publish(any(), any())).thenReturn(PublishResult.timeout());
        updateMatches(0);

        CreateNotificationResponse response = service.create(REQUEST);

        assertThat(response.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(response.id()).isNotBlank();
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(NotificationDocument.class));
    }

    @Test
    void confirmedPublishNeverUpdatesStatus() {
        when(publisher.publish(any(), any())).thenReturn(PublishResult.acked());

        CreateNotificationResponse response = service.create(REQUEST);

        assertThat(response.status()).isEqualTo(NotificationStatus.PENDING);
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), any(Class.class));
    }
}
