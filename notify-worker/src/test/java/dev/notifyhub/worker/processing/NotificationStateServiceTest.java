package dev.notifyhub.worker.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import dev.notifyhub.common.domain.AttemptOutcome;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.FailureClass;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.persistence.AttemptEntry;
import dev.notifyhub.common.persistence.LastError;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.common.persistence.RenderedContent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/** Claim e resultados SENT/FAILED como operações únicas e cercadas no MongoTemplate (WRK-02, WRK-04, DLV-06). */
class NotificationStateServiceTest {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final NotificationStateService service = new NotificationStateService(mongo, LEASE);

    // ---------------------------------------------------------------- claim

    private record Claim(Query query, UpdateDefinition update, FindAndModifyOptions options) {
    }

    private Claim captureClaim(int retryCount) {
        service.claim("n-1", retryCount);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        ArgumentCaptor<FindAndModifyOptions> options = ArgumentCaptor.forClass(FindAndModifyOptions.class);
        verify(mongo).findAndModify(query.capture(), update.capture(), options.capture(),
                eq(NotificationDocument.class));
        return new Claim(query.getValue(), update.getValue(), options.getValue());
    }

    @Test
    void claimFilterIsIdClaimableStatusesAndInclusiveRetryLevel() {
        Document filter = MongoBson.query(captureClaim(2).query());

        assertThat(filter.keySet()).containsExactly("_id", "status", "retryCount");
        assertThat(filter.get("_id")).isEqualTo("n-1");
        assertThat(filter.get("status", Document.class).getList("$in", Object.class))
                .containsExactlyInAnyOrder("PENDING", "RETRYING", "PUBLISH_FAILED");
        assertThat(filter.get("retryCount", Document.class)).isEqualTo(new Document("$lte", 2));
    }

    @Test
    void claimStatusFilterIsDerivedFromTheTransitionTable() {
        Document filter = MongoBson.query(captureClaim(0).query());

        List<String> expected = NotificationStatus.claimableForDelivery().stream().map(Enum::name).toList();
        assertThat(filter.get("status", Document.class).getList("$in", Object.class))
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void claimSetsProcessingRetryLevelClaimLeaseAndUpdatedAt() {
        Claim claim = captureClaim(2);
        Document update = claim.update().getUpdateObject();

        assertThat(update.keySet()).containsExactly("$set");
        Document set = update.get("$set", Document.class);
        assertThat(set.keySet()).containsExactlyInAnyOrder(
                "status", "retryCount", "claimedAt", "leaseUntil", "updatedAt");
        assertThat(set.get("status")).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(set.get("retryCount")).isEqualTo(2);
        Instant claimedAt = (Instant) set.get("claimedAt");
        assertThat((Instant) set.get("leaseUntil")).isEqualTo(claimedAt.plus(LEASE));
        assertThat((Instant) set.get("updatedAt")).isEqualTo(claimedAt);
    }

    @Test
    void claimReturnsTheNewDocument() {
        assertThat(captureClaim(0).options().isReturnNew()).isTrue();
    }

    @Test
    void claimMatchingNothingIsEmpty() {
        when(mongo.findAndModify(any(Query.class), any(UpdateDefinition.class),
                any(FindAndModifyOptions.class), eq(NotificationDocument.class))).thenReturn(null);

        assertThat(service.claim("n-1", 0)).isEmpty();
    }

    @Test
    void claimReturnsTheClaimedDocument() {
        NotificationDocument processing = new NotificationDocument("n-1", Channel.SMS, "+5511999998888",
                "welcome", Map.of("name", "Ana"), NotificationStatus.PROCESSING, 1, 1, null, null,
                List.of(), NOW, NOW, NOW, NOW.plus(LEASE), null);
        when(mongo.findAndModify(any(Query.class), any(UpdateDefinition.class),
                any(FindAndModifyOptions.class), eq(NotificationDocument.class))).thenReturn(processing);

        assertThat(service.claim("n-1", 1)).isEqualTo(Optional.of(processing));
    }

    // ---------------------------------------------------------------- markSent

    private static final RenderedContent RENDERED = new RenderedContent("Olá, Ana", "<p>Olá, Ana</p>");
    private static final AttemptEntry ATTEMPT = new AttemptEntry(
            4, 2, NOW, 120, AttemptOutcome.SUCCESS, null, null, null, "VirtualThread[#42]");

    private void updateFirstModifies(long modified) {
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class)))
                .thenReturn(UpdateResult.acknowledged(modified, modified, null));
    }

    private record Result(Query query, UpdateDefinition update) {
    }

    private Result captureResult() {
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongo).updateFirst(query.capture(), update.capture(), eq(NotificationDocument.class));
        return new Result(query.getValue(), update.getValue());
    }

    @Test
    void markSentIsFencedByExactStatusAndRetryLevel() {
        updateFirstModifies(1);

        assertThat(service.markSent("n-1", 2, RENDERED, ATTEMPT)).isTrue();

        Document filter = MongoBson.query(captureResult().query());
        assertThat(filter).isEqualTo(new Document("_id", "n-1")
                .append("status", "PROCESSING")
                .append("retryCount", 2));
    }

    @Test
    void markSentWritesEverythingInOneUpdateWithBoundedHistory() {
        updateFirstModifies(1);

        service.markSent("n-1", 2, RENDERED, ATTEMPT);

        UpdateDefinition update = captureResult().update();
        Document raw = update.getUpdateObject();
        assertThat(raw.keySet()).containsExactlyInAnyOrder("$set", "$unset", "$inc", "$push");

        Document set = raw.get("$set", Document.class);
        assertThat(set.keySet()).containsExactlyInAnyOrder("status", "rendered", "sentAt", "updatedAt");
        assertThat(set.get("status")).isEqualTo(NotificationStatus.SENT);
        assertThat(set.get("rendered")).isEqualTo(RENDERED);
        assertThat(set.get("sentAt")).isEqualTo(set.get("updatedAt"));
        assertThat(raw.get("$unset", Document.class).keySet())
                .containsExactlyInAnyOrder("lastError", "leaseUntil");
        assertThat(raw.get("$inc", Document.class)).isEqualTo(new Document("attemptCount", 1));

        Document push = MongoBson.update(update).get("$push", Document.class);
        assertThat(push.keySet()).containsExactly("attempts");
        Document attempts = push.get("attempts", Document.class);
        assertThat(attempts.get("$slice")).isEqualTo(-50);
        List<?> each = attempts.get("$each", List.class);
        assertThat(each).hasSize(1);
        Document attempt = (Document) each.get(0);
        assertThat(attempt.get("number")).isEqualTo(4);
        assertThat(attempt.get("retryLevel")).isEqualTo(2);
        assertThat(attempt.get("outcome")).isEqualTo("SUCCESS");
        assertThat(attempt.get("thread")).isEqualTo("VirtualThread[#42]");
    }

    @Test
    void markSentTouchesMongoExactlyOnce() {
        updateFirstModifies(1);

        service.markSent("n-1", 2, RENDERED, ATTEMPT);

        verify(mongo).updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class));
        verifyNoMoreInteractions(mongo);
    }

    @Test
    void lostFenceReturnsFalseWithoutThrowing() {
        updateFirstModifies(0);

        assertThat(service.markSent("n-1", 2, RENDERED, ATTEMPT)).isFalse();
        verify(mongo).updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class));
        verifyNoMoreInteractions(mongo);
    }

    // ---------------------------------------------------------------- markFailed

    private static final AttemptEntry FAILED_ATTEMPT = new AttemptEntry(
            3, 1, NOW, 0, AttemptOutcome.PERMANENT_FAILURE, "TEMPLATE_NOT_FOUND",
            "template não encontrado: x", FailureClass.PERMANENT, "VirtualThread[#42]");
    private static final LastError LAST_ERROR = new LastError(
            "TEMPLATE_NOT_FOUND", "template não encontrado: x", FailureClass.PERMANENT, NOW);

    @Test
    void markFailedIsFencedByExactStatusAndRetryLevel() {
        updateFirstModifies(1);

        assertThat(service.markFailed("n-1", 1, FAILED_ATTEMPT, LAST_ERROR)).isTrue();

        Document filter = MongoBson.query(captureResult().query());
        assertThat(filter.keySet()).containsExactly("_id", "status", "retryCount");
        assertThat(filter).isEqualTo(new Document("_id", "n-1")
                .append("status", "PROCESSING")
                .append("retryCount", 1));
    }

    @Test
    void markFailedWritesStatusErrorAndBoundedHistoryInOneUpdate() {
        updateFirstModifies(1);

        service.markFailed("n-1", 1, FAILED_ATTEMPT, LAST_ERROR);

        UpdateDefinition update = captureResult().update();
        Document raw = update.getUpdateObject();
        assertThat(raw.keySet()).containsExactlyInAnyOrder("$set", "$unset", "$inc", "$push");

        Document set = raw.get("$set", Document.class);
        assertThat(set.keySet()).containsExactlyInAnyOrder("status", "lastError", "updatedAt");
        assertThat(set.get("status")).isEqualTo(NotificationStatus.FAILED);
        assertThat(set.get("lastError")).isEqualTo(LAST_ERROR);
        assertThat(raw.get("$unset", Document.class).keySet()).containsExactly("leaseUntil");
        assertThat(raw.get("$inc", Document.class)).isEqualTo(new Document("attemptCount", 1));

        Document attempts = MongoBson.update(update).get("$push", Document.class).get("attempts", Document.class);
        assertThat(attempts.get("$slice")).isEqualTo(-50);
        List<?> each = attempts.get("$each", List.class);
        assertThat(each).hasSize(1);
        Document attempt = (Document) each.get(0);
        assertThat(attempt.get("outcome")).isEqualTo("PERMANENT_FAILURE");
        assertThat(attempt.get("errorCode")).isEqualTo("TEMPLATE_NOT_FOUND");
        assertThat(attempt.get("failureClass")).isEqualTo("PERMANENT");
    }

    @Test
    void markFailedWithLostFenceReturnsFalseWithoutThrowing() {
        updateFirstModifies(0);

        assertThat(service.markFailed("n-1", 1, FAILED_ATTEMPT, LAST_ERROR)).isFalse();
        verify(mongo).updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class));
        verifyNoMoreInteractions(mongo);
    }
}
