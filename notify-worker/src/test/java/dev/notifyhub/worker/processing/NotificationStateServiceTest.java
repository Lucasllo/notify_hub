package dev.notifyhub.worker.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
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
import java.time.temporal.ChronoUnit;
import java.util.Date;
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
        return captureClaim(retryCount, false);
    }

    private Claim captureClaim(int retryCount, boolean redelivered) {
        service.claim("n-1", retryCount, redelivered);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        ArgumentCaptor<FindAndModifyOptions> options = ArgumentCaptor.forClass(FindAndModifyOptions.class);
        verify(mongo).findAndModify(query.capture(), update.capture(), options.capture(),
                eq(NotificationDocument.class));
        return new Claim(query.getValue(), update.getValue(), options.getValue());
    }

    private static List<Document> orBranches(Document filter) {
        return filter.getList("$or", Document.class);
    }

    private static List<Object> statusIn(Document branch) {
        return branch.get("status", Document.class).getList("$in", Object.class);
    }

    private static Instant asInstant(Object value) {
        return value instanceof Date date ? date.toInstant() : (Instant) value;
    }

    @Test
    void claimFilterIsIdInclusiveRetryLevelAndTwoStatusBranches() {
        Document filter = MongoBson.query(captureClaim(2).query());

        assertThat(filter.keySet()).containsExactlyInAnyOrder("_id", "retryCount", "$or");
        assertThat(filter.get("_id")).isEqualTo("n-1");
        // o fence de nível vale nos dois ramos (fica fora do $or)
        assertThat(filter.get("retryCount", Document.class)).isEqualTo(new Document("$lte", 2));
        assertThat(orBranches(filter)).hasSize(2);
        assertThat(statusIn(orBranches(filter).get(0)))
                .containsExactlyInAnyOrder("PENDING", "RETRYING", "PUBLISH_FAILED");
    }

    @Test
    void redeliveredClaimReclaimsProcessingWithoutLeaseCondition() {
        Document filter = MongoBson.query(captureClaim(2, true).query());

        Document stuck = orBranches(filter).get(1);
        assertThat(stuck.keySet()).containsExactly("status");
        assertThat(statusIn(stuck)).containsExactly("PROCESSING");
        assertThat(filter.get("retryCount", Document.class)).isEqualTo(new Document("$lte", 2));
    }

    @Test
    void firstDeliveryOnlyReclaimsProcessingWhoseLeaseHasExpired() {
        Instant before = Instant.now();
        Document filter = MongoBson.query(captureClaim(2, false).query());
        Instant after = Instant.now();

        Document stuck = orBranches(filter).get(1);
        assertThat(stuck.keySet()).containsExactlyInAnyOrder("status", "leaseUntil");
        assertThat(statusIn(stuck)).containsExactly("PROCESSING");
        Instant leaseCutoff = asInstant(stuck.get("leaseUntil", Document.class).get("$lt"));
        // lease vigente (leaseUntil ≥ agora) não casa; só lease vencido
        assertThat(leaseCutoff).isBetween(before.truncatedTo(ChronoUnit.MILLIS), after);
        assertThat(filter.get("retryCount", Document.class)).isEqualTo(new Document("$lte", 2));
    }

    @Test
    void claimStatusBranchesAreDerivedFromTheTransitionTable() {
        Document filter = MongoBson.query(captureClaim(0, true).query());

        List<String> fresh = NotificationStatus.claimableForDelivery().stream().map(Enum::name).toList();
        List<String> stuck = NotificationStatus.reclaimableWhenStuck().stream().map(Enum::name).toList();
        assertThat(statusIn(orBranches(filter).get(0))).containsExactlyInAnyOrderElementsOf(fresh);
        assertThat(statusIn(orBranches(filter).get(1))).containsExactlyInAnyOrderElementsOf(stuck);
    }

    @Test
    void redeliveredAndExpiredLeaseClaimsWriteTheSameUpdate() {
        Document redelivered = captureClaim(1, true).update().getUpdateObject();
        clearInvocations(mongo);
        Document expired = captureClaim(1, false).update().getUpdateObject();

        assertThat(redelivered.get("$set", Document.class).keySet())
                .isEqualTo(expired.get("$set", Document.class).keySet());
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

        assertThat(service.claim("n-1", 0, false)).isEmpty();
    }

    @Test
    void claimReturnsTheClaimedDocument() {
        NotificationDocument processing = new NotificationDocument("n-1", Channel.SMS, "+5511999998888",
                "welcome", Map.of("name", "Ana"), NotificationStatus.PROCESSING, 1, 1, null, null,
                List.of(), NOW, NOW, NOW, NOW.plus(LEASE), null);
        when(mongo.findAndModify(any(Query.class), any(UpdateDefinition.class),
                any(FindAndModifyOptions.class), eq(NotificationDocument.class))).thenReturn(processing);

        assertThat(service.claim("n-1", 1, true)).isEqualTo(Optional.of(processing));
    }

    // ---------------------------------------------------------------- casamento do claim (WR-01, D-09)

    private Document claimFilter(int retryCount, boolean redelivered) {
        Document filter = MongoBson.query(captureClaim(retryCount, redelivered).query());
        clearInvocations(mongo);
        return filter;
    }

    private static Document doc(NotificationStatus status, int retryCount, Instant leaseUntil) {
        return SingleNotificationStore.of(status, retryCount, 0, leaseUntil, "ana@exemplo.com").snapshot();
    }

    @Test
    void retryingWhoseFenceAdvancedIsNotClaimedByTheLevelBelow() {
        Document advanced = doc(NotificationStatus.RETRYING, 1, null);

        assertThat(SingleNotificationStore.matches(claimFilter(0, true), advanced)).isFalse();
        assertThat(SingleNotificationStore.matches(claimFilter(0, false), advanced)).isFalse();
        assertThat(SingleNotificationStore.matches(claimFilter(1, false), advanced)).isTrue();
    }

    @Test
    void retryingAtTheSameLevelIsStillClaimedByTheRedeliveredOriginal() {
        assertThat(SingleNotificationStore.matches(claimFilter(0, true),
                doc(NotificationStatus.RETRYING, 0, null))).isTrue();
    }

    @Test
    void processingWithALiveLeaseIsOnlyClaimedByARedeliveredCopy() {
        Document live = doc(NotificationStatus.PROCESSING, 0, Instant.now().plus(LEASE));

        assertThat(SingleNotificationStore.matches(claimFilter(0, true), live)).isTrue();
        assertThat(SingleNotificationStore.matches(claimFilter(0, false), live)).isFalse();
    }

    @Test
    void processingWithAnExpiredLeaseIsClaimedByAFirstDelivery() {
        Document expired = doc(NotificationStatus.PROCESSING, 0, Instant.now().minus(Duration.ofMinutes(1)));

        assertThat(SingleNotificationStore.matches(claimFilter(0, false), expired)).isTrue();
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

    // ---------------------------------------------------------------- markRetrying / markDeadLettered (D-11)

    private static final AttemptEntry TRANSIENT_ATTEMPT = new AttemptEntry(
            2, 1, NOW, 35, AttemptOutcome.TRANSIENT_FAILURE, "SIMULATED_TRANSIENT",
            "falha simulada no canal email", FailureClass.TRANSIENT, "VirtualThread[#42]");
    private static final LastError TRANSIENT_ERROR = new LastError(
            "SIMULATED_TRANSIENT", "falha simulada no canal email", FailureClass.TRANSIENT, NOW);

    private void assertTransientResultShape(NotificationStatus target) {
        Result result = captureResult();

        Document filter = MongoBson.query(result.query());
        assertThat(filter.keySet()).containsExactly("_id", "status", "retryCount");
        assertThat(filter).isEqualTo(new Document("_id", "n-1")
                .append("status", "PROCESSING")
                .append("retryCount", 1));

        Document raw = result.update().getUpdateObject();
        assertThat(raw.keySet()).containsExactlyInAnyOrder("$set", "$unset", "$inc", "$push");
        Document set = raw.get("$set", Document.class);
        // retryCount nunca é gravado no resultado: quem grava h+1 é o claim do próximo token (Armadilha 2)
        assertThat(set.keySet()).containsExactlyInAnyOrder("status", "lastError", "updatedAt");
        assertThat(set.get("status")).isEqualTo(target);
        assertThat(set.get("lastError")).isEqualTo(TRANSIENT_ERROR);
        assertThat(raw.get("$unset", Document.class).keySet()).containsExactly("leaseUntil");
        assertThat(raw.get("$inc", Document.class)).isEqualTo(new Document("attemptCount", 1));

        Document attempts = MongoBson.update(result.update()).get("$push", Document.class)
                .get("attempts", Document.class);
        assertThat(attempts.keySet()).containsExactlyInAnyOrder("$each", "$slice");
        assertThat(attempts.get("$slice")).isEqualTo(-50);
        List<?> each = attempts.get("$each", List.class);
        assertThat(each).hasSize(1);
        Document attempt = (Document) each.get(0);
        assertThat(attempt.get("outcome")).isEqualTo("TRANSIENT_FAILURE");
        assertThat(attempt.get("retryLevel")).isEqualTo(1);
    }

    @Test
    void markRetryingIsOneFencedUpdateThatNeverWritesRetryCount() {
        updateFirstModifies(1);

        assertThat(service.markRetrying("n-1", 1, TRANSIENT_ATTEMPT, TRANSIENT_ERROR)).isTrue();

        assertTransientResultShape(NotificationStatus.RETRYING);
    }

    @Test
    void markDeadLetteredIsOneFencedUpdateWithTheLastAttempt() {
        updateFirstModifies(1);

        assertThat(service.markDeadLettered("n-1", 1, TRANSIENT_ATTEMPT, TRANSIENT_ERROR)).isTrue();

        assertTransientResultShape(NotificationStatus.DEAD_LETTERED);
    }

    @Test
    void markRetryingAndMarkDeadLetteredWithLostFenceReturnFalse() {
        updateFirstModifies(0);

        assertThat(service.markRetrying("n-1", 1, TRANSIENT_ATTEMPT, TRANSIENT_ERROR)).isFalse();
        assertThat(service.markDeadLettered("n-1", 1, TRANSIENT_ATTEMPT, TRANSIENT_ERROR)).isFalse();
    }

    // ---------------------------------------------------------------- avanço do fence do retry (WR-01)

    @Test
    void advanceRetryLevelIsOneUpdateFencedByRetryingAndTheSameLevel() {
        updateFirstModifies(1);

        assertThat(service.advanceRetryLevel("n-1", 1, 2)).isTrue();

        Result result = captureResult();
        Document filter = MongoBson.query(result.query());
        assertThat(filter.keySet()).containsExactly("_id", "status", "retryCount");
        assertThat(filter).isEqualTo(new Document("_id", "n-1")
                .append("status", "RETRYING")
                .append("retryCount", 1));
        Document raw = result.update().getUpdateObject();
        // não é transição de status: só o nível e updatedAt
        assertThat(raw.keySet()).containsExactly("$set");
        Document set = raw.get("$set", Document.class);
        assertThat(set.keySet()).containsExactlyInAnyOrder("retryCount", "updatedAt");
        assertThat(set.get("retryCount")).isEqualTo(2);
        verifyNoMoreInteractions(mongo);
    }

    @Test
    void advanceRetryLevelWithLostFenceReturnsFalseWithoutThrowing() {
        updateFirstModifies(0);

        assertThat(service.advanceRetryLevel("n-1", 1, 2)).isFalse();
        verify(mongo).updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class));
        verifyNoMoreInteractions(mongo);
    }

    // ---------------------------------------------------------------- republicação falha (D-11, D-17)

    private static final LastError REPUBLISH_ERROR = new LastError(
            "REPUBLISH_FAILED", "republicação para retry.25s falhou: devolvida: NO_ROUTE", FailureClass.TRANSIENT, NOW);

    @Test
    void deadLetteringAfterRepublishFailureIsFencedByRetryingAndTheSameLevel() {
        updateFirstModifies(1);

        assertThat(service.markDeadLetteredAfterRepublishFailure("n-1", 1, REPUBLISH_ERROR)).isTrue();

        Document filter = MongoBson.query(captureResult().query());
        assertThat(filter.keySet()).containsExactly("_id", "status", "retryCount");
        assertThat(filter).isEqualTo(new Document("_id", "n-1")
                .append("status", "RETRYING")
                .append("retryCount", 1));
    }

    @Test
    void deadLetteringAfterRepublishFailureOnlySetsStatusAndLastErrorWithoutANewAttempt() {
        updateFirstModifies(1);

        service.markDeadLetteredAfterRepublishFailure("n-1", 1, REPUBLISH_ERROR);

        UpdateDefinition update = captureResult().update();
        Document raw = update.getUpdateObject();
        // D-17: a tentativa já foi registrada no RETRYING; sem $push e sem $inc
        assertThat(raw.keySet()).containsExactly("$set");
        Document set = raw.get("$set", Document.class);
        assertThat(set.keySet()).containsExactlyInAnyOrder("status", "lastError", "updatedAt");
        assertThat(set.get("status")).isEqualTo(NotificationStatus.DEAD_LETTERED);

        Document lastError = MongoBson.update(update).get("$set", Document.class).get("lastError", Document.class);
        assertThat(lastError.getString("code")).isEqualTo("REPUBLISH_FAILED");
        assertThat(lastError.get("failureClass")).isEqualTo("TRANSIENT");
    }

    @Test
    void deadLetteringAfterRepublishFailureWithLostFenceReturnsFalse() {
        updateFirstModifies(0);

        assertThat(service.markDeadLetteredAfterRepublishFailure("n-1", 1, REPUBLISH_ERROR)).isFalse();
        verify(mongo).updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class));
        verifyNoMoreInteractions(mongo);
    }
}
