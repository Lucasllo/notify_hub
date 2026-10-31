package dev.notifyhub.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import dev.notifyhub.api.messaging.NotificationPublisher;
import dev.notifyhub.api.messaging.PublishProperties;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.persistence.LastError;
import dev.notifyhub.common.persistence.NotificationDocument;
import java.time.Duration;
import java.time.Instant;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Varredura de PENDING sem confirm (Gap 1 do 03-VERIFICATION, T-03-32, T-03-41): filtro exato do
 * {@code updateMulti}, MongoDB fora numa varredura e validação do corte no startup. Sem Docker, com
 * {@code MongoTemplate} e {@code NotificationPublisher} mockados e o {@link NotificationRepublisher}
 * real, no molde do {@code NotificationRepublisherTest}.
 */
class UnconfirmedPublishSweeperTest {

    private static final Duration AFTER = Duration.ofMinutes(2);
    private static final PublishProperties PUBLISH = new PublishProperties(Duration.ofSeconds(5), Duration.ofSeconds(5));

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final NotificationRepublisher republisher = new NotificationRepublisher(mongo, publisher, PUBLISH);

    private UnconfirmedPublishSweeper sweeper(Duration after) {
        return new UnconfirmedPublishSweeper(republisher, new UnconfirmedSweepProperties(after, Duration.ofSeconds(30)),
                PUBLISH);
    }

    @Test
    void sweepMarksOnlyOldUnclaimedPendingAsPublishFailed() {
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(NotificationDocument.class)))
                .thenReturn(UpdateResult.acknowledged(2, 2L, null));
        UnconfirmedPublishSweeper sweeper = sweeper(AFTER);

        Instant before = Instant.now();
        long marked = sweeper.sweep();
        Instant after = Instant.now();

        assertThat(marked).isEqualTo(2);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo, times(1)).updateMulti(query.capture(), update.capture(), eq(NotificationDocument.class));

        Document filter = query.getValue().getQueryObject();
        assertThat(filter.keySet()).containsExactlyInAnyOrder("status", "updatedAt", "claimedAt");
        assertThat(String.valueOf(filter.get("status"))).isEqualTo("PENDING");
        Document updatedAt = (Document) filter.get("updatedAt");
        assertThat(updatedAt.keySet()).containsExactly("$lt");
        Instant cutoff = (Instant) updatedAt.get("$lt");
        assertThat(cutoff).isBetween(before.minus(AFTER), after.minus(AFTER));
        assertThat(filter.containsKey("claimedAt")).isTrue();
        assertThat(filter.get("claimedAt")).isNull();

        assertThat(update.getValue().getUpdateObject().keySet()).containsExactly("$set");
        Document set = (Document) update.getValue().getUpdateObject().get("$set");
        assertThat(set.keySet()).containsExactlyInAnyOrder("status", "lastError", "updatedAt");
        assertThat(set.get("status")).isEqualTo(NotificationStatus.PUBLISH_FAILED);
        LastError lastError = (LastError) set.get("lastError");
        assertThat(lastError.code()).isEqualTo("PUBLISH_UNCONFIRMED");
        assertThat(lastError.message()).isEqualTo("Publicação não confirmada pelo broker (PUBLISH_UNCONFIRMED)");
        assertThat(lastError.failureClass()).isNull();
        assertThat(set.get("updatedAt")).isInstanceOf(Instant.class);
    }

    @Test
    void mongoDownDuringASweepIsLoggedAndReturnsZero() {
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(NotificationDocument.class)))
                .thenThrow(new DataAccessResourceFailureException("conexão recusada"));
        UnconfirmedPublishSweeper sweeper = sweeper(AFTER);

        long[] marked = new long[1];
        assertThatCode(() -> marked[0] = sweeper.sweep()).doesNotThrowAnyException();
        assertThat(marked[0]).isZero();
    }

    @Test
    void afterShorterThanThreeConfirmTimeoutsFailsAtStartup() {
        assertThatThrownBy(() -> sweeper(Duration.ofSeconds(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notify.publish.unconfirmed-sweep.after")
                .hasMessageContaining("notify.publish.confirm-timeout");

        assertThatCode(() -> sweeper(Duration.ofSeconds(15))).doesNotThrowAnyException();
    }
}
