package dev.notifyhub.worker.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import dev.notifyhub.common.domain.AttemptOutcome;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.FailureClass;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.messaging.TopologyProperties;
import dev.notifyhub.common.persistence.AttemptEntry;
import dev.notifyhub.common.persistence.LastError;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.common.persistence.RenderedContent;
import dev.notifyhub.worker.channel.AttemptContext;
import dev.notifyhub.worker.channel.SimulatedDeliveryException;
import dev.notifyhub.worker.failure.FailureClassifier;
import dev.notifyhub.worker.messaging.DeliveryRoutingPublisher;
import dev.notifyhub.worker.messaging.DeliveryRoutingPublisher.PublishOutcome;
import dev.notifyhub.worker.metrics.DeliveryMetrics;
import dev.notifyhub.worker.resilience.ChannelGateway;
import dev.notifyhub.worker.template.TemplateCatalog;
import dev.notifyhub.worker.template.TemplateDocument;
import dev.notifyhub.worker.template.TemplateNotFoundException;
import dev.notifyhub.worker.template.TemplateRenderer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

/**
 * Orquestração do processador: duplicatas, canal errado, classificação das falhas, FAILED + cópia na
 * DLQ, fallback de reject e número da tentativa.
 */
class DeliveryProcessorTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final Map<String, Object> VARIABLES = Map.of("name", "Ana");
    private static final TemplateDocument TEMPLATE = new TemplateDocument("welcome", 1, List.of("name"), Map.of());
    private static final RenderedContent RENDERED = new RenderedContent("Olá, Ana", "<p>Olá, Ana</p>");
    private static final TopologyProperties TOPOLOGY = new TopologyProperties(List.of(
            Duration.ofSeconds(5), Duration.ofSeconds(25), Duration.ofSeconds(125)));

    private final NotificationStateService state = mock(NotificationStateService.class);
    private final TemplateCatalog catalog = mock(TemplateCatalog.class);
    private final TemplateRenderer renderer = mock(TemplateRenderer.class);
    private final ChannelGateway gateway = mock(ChannelGateway.class);
    private final FailureClassifier classifier = new FailureClassifier();
    private final DeliveryRoutingPublisher publisher = mock(DeliveryRoutingPublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DeliveryMetrics metrics = new DeliveryMetrics(meters, TOPOLOGY, classifier);
    private final DeliveryProcessor processor =
            new DeliveryProcessor(state, catalog, renderer, gateway, classifier, publisher, TOPOLOGY, metrics);

    private static NotificationMessage emailMessage() {
        return new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW);
    }

    private static NotificationDocument claimedEmail(int retryCount, int attemptCount) {
        return new NotificationDocument("n-1", Channel.EMAIL, "ana@exemplo.com", "welcome", VARIABLES,
                NotificationStatus.PROCESSING, retryCount, attemptCount, null, null, List.of(),
                NOW, NOW, NOW, NOW, null);
    }

    private void claimReturns(NotificationDocument doc) {
        when(state.claim("n-1", doc.retryCount(), false)).thenReturn(Optional.of(doc));
        when(catalog.require("welcome")).thenReturn(TEMPLATE);
        when(renderer.render(TEMPLATE, Channel.EMAIL, VARIABLES, "n-1")).thenReturn(RENDERED);
        when(state.markSent(eq("n-1"), anyInt(), any(), any())).thenReturn(true);
    }

    @Test
    void duplicateOrStaleMessageIsAckedWithoutSending() {
        when(state.claim("n-1", 0, false)).thenReturn(Optional.empty());

        assertThatCode(() -> processor.process(Channel.EMAIL, emailMessage(), 0, false)).doesNotThrowAnyException();

        verifyNoInteractions(catalog, renderer);
        verify(gateway, never()).send(any(), anyString(), any(), any());
        verify(state, never()).markSent(anyString(), anyInt(), any(), any());
    }

    @Test
    void redeliveredFlagReachesTheClaimAndTheResumedDeliveryEndsSent() {
        NotificationDocument stuck = claimedEmail(0, 1);
        claimReturns(stuck);
        when(state.claim("n-1", 0, true)).thenReturn(Optional.of(stuck));

        assertThatCode(() -> processor.process(Channel.EMAIL, emailMessage(), 0, true)).doesNotThrowAnyException();

        verify(state).claim("n-1", 0, true);
        verify(state, never()).claim("n-1", 0, false);
        verify(gateway).send(eq(Channel.EMAIL), eq("ana@exemplo.com"), eq(RENDERED), any());
        verify(state).markSent(eq("n-1"), eq(0), eq(RENDERED), any());
    }

    @Test
    void sentIsCountedOnlyAfterTheSentUpdateWinsTheFence() {
        claimReturns(claimedEmail(0, 0));

        processor.process(Channel.EMAIL, emailMessage(), 0, false);

        assertThat(outcome("sent", "none")).isEqualTo(1);
        assertThat(allResultCounters()).isEqualTo(1);
    }

    @Test
    void lostFenceOnSentCountsNothing() {
        claimReturns(claimedEmail(0, 0));
        when(state.markSent(eq("n-1"), anyInt(), any(), any())).thenReturn(false);

        processor.process(Channel.EMAIL, emailMessage(), 0, false);

        assertThat(allResultCounters()).isZero();
    }

    @Test
    void firstDeliveryClaimsWithoutTheRedeliveredFlag() {
        claimReturns(claimedEmail(0, 0));

        processor.process(Channel.EMAIL, emailMessage(), 0, false);

        verify(state).claim("n-1", 0, false);
        verify(state, never()).claim("n-1", 0, true);
    }

    @Test
    void messageFromAnotherChannelIsRejectedWithoutClaiming() {
        assertThatThrownBy(() -> processor.process(Channel.SMS, emailMessage(), 0, false))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(state, never()).claim(anyString(), anyInt(), anyBoolean());
    }

    @Test
    void missingTemplateIsMarkedFailedAndCopiedToTheDlqWithoutThrowing() {
        when(state.claim("n-1", 0, false)).thenReturn(Optional.of(claimedEmail(0, 0)));
        when(catalog.require("welcome")).thenThrow(TemplateNotFoundException.missing("welcome"));
        when(state.markFailed(eq("n-1"), eq(0), any(), any())).thenReturn(true);
        when(publisher.publishToDlq(any(), any(), any(), anyInt())).thenReturn(PublishOutcome.acked());

        assertThatCode(() -> processor.process(Channel.EMAIL, emailMessage(), 0, false)).doesNotThrowAnyException();

        ArgumentCaptor<AttemptEntry> attempt = ArgumentCaptor.forClass(AttemptEntry.class);
        ArgumentCaptor<LastError> lastError = ArgumentCaptor.forClass(LastError.class);
        verify(state).markFailed(eq("n-1"), eq(0), attempt.capture(), lastError.capture());
        assertThat(attempt.getValue().number()).isEqualTo(1);
        assertThat(attempt.getValue().outcome()).isEqualTo(AttemptOutcome.PERMANENT_FAILURE);
        assertThat(attempt.getValue().failureClass()).isEqualTo(FailureClass.PERMANENT);
        assertThat(attempt.getValue().errorCode()).isEqualTo("TEMPLATE_NOT_FOUND");
        assertThat(attempt.getValue().durationMs()).isZero();
        assertThat(lastError.getValue().code()).isEqualTo("TEMPLATE_NOT_FOUND");
        verify(publisher).publishToDlq(Channel.EMAIL, emailMessage(), FailureClass.PERMANENT, 0);
        verify(gateway, never()).send(any(), anyString(), any(), any());
        verify(state, never()).markSent(anyString(), anyInt(), any(), any());
    }

    @Test
    void transientAdapterFailureIsMarkedRetryingAndRepublishedWithoutThrowing() {
        claimReturns(claimedEmail(0, 0));
        doThrow(new SimulatedDeliveryException("falha simulada no canal email"))
                .when(gateway).send(eq(Channel.EMAIL), eq("ana@exemplo.com"), eq(RENDERED), any());
        when(state.markRetrying(eq("n-1"), eq(0), any(), any())).thenReturn(true);
        when(publisher.publishToRetry(any(), any(), anyInt(), any())).thenReturn(PublishOutcome.acked());

        assertThatCode(() -> processor.process(Channel.EMAIL, emailMessage(), 0, false)).doesNotThrowAnyException();

        ArgumentCaptor<AttemptEntry> attempt = ArgumentCaptor.forClass(AttemptEntry.class);
        ArgumentCaptor<LastError> lastError = ArgumentCaptor.forClass(LastError.class);
        verify(state).markRetrying(eq("n-1"), eq(0), attempt.capture(), lastError.capture());
        assertThat(attempt.getValue().outcome()).isEqualTo(AttemptOutcome.TRANSIENT_FAILURE);
        assertThat(attempt.getValue().failureClass()).isEqualTo(FailureClass.TRANSIENT);
        assertThat(lastError.getValue().code()).isEqualTo("SIMULATED_TRANSIENT");
        verify(publisher).publishToRetry(Channel.EMAIL, emailMessage(), 1, Duration.ofSeconds(5));
        verify(state, never()).markSent(anyString(), anyInt(), any(), any());
        verify(state, never()).markFailed(anyString(), anyInt(), any(), any());
        verify(publisher, never()).publishToDlq(any(), any(), any(), anyInt());
    }

    private void adapterFailsTransiently(int retryCount) {
        claimReturns(claimedEmail(retryCount, retryCount));
        doThrow(new SimulatedDeliveryException("falha simulada no canal email"))
                .when(gateway).send(eq(Channel.EMAIL), eq("ana@exemplo.com"), eq(RENDERED), any());
    }

    @Test
    void exhaustedLadderIsDeadLetteredAndCopiedToTheDlqAsTransient() {
        adapterFailsTransiently(3);
        when(state.markDeadLettered(eq("n-1"), eq(3), any(), any())).thenReturn(true);
        when(publisher.publishToDlq(any(), any(), any(), anyInt())).thenReturn(PublishOutcome.acked());

        assertThatCode(() -> processor.process(Channel.EMAIL, emailMessage(), 3, false)).doesNotThrowAnyException();

        ArgumentCaptor<AttemptEntry> attempt = ArgumentCaptor.forClass(AttemptEntry.class);
        ArgumentCaptor<LastError> lastError = ArgumentCaptor.forClass(LastError.class);
        verify(state).markDeadLettered(eq("n-1"), eq(3), attempt.capture(), lastError.capture());
        assertThat(attempt.getValue().number()).isEqualTo(4);
        assertThat(attempt.getValue().retryLevel()).isEqualTo(3);
        assertThat(attempt.getValue().outcome()).isEqualTo(AttemptOutcome.TRANSIENT_FAILURE);
        assertThat(lastError.getValue().failureClass()).isEqualTo(FailureClass.TRANSIENT);
        verify(publisher).publishToDlq(Channel.EMAIL, emailMessage(), FailureClass.TRANSIENT, 3);
        verify(publisher, never()).publishToRetry(any(), any(), anyInt(), any());
        verify(state, never()).markRetrying(anyString(), anyInt(), any(), any());
        verify(state, never()).markFailed(anyString(), anyInt(), any(), any());
    }

    @Test
    void tamperedLevelAboveTheLadderIsDeadLetteredToo() {
        adapterFailsTransiently(7);
        when(state.markDeadLettered(eq("n-1"), eq(7), any(), any())).thenReturn(true);
        when(publisher.publishToDlq(any(), any(), any(), anyInt())).thenReturn(PublishOutcome.acked());

        processor.process(Channel.EMAIL, emailMessage(), 7, false);

        verify(publisher).publishToDlq(Channel.EMAIL, emailMessage(), FailureClass.TRANSIENT, 7);
        verify(publisher, never()).publishToRetry(any(), any(), anyInt(), any());
    }

    @Test
    void unconfirmedDlqCopyAfterExhaustionFallsBackToReject() {
        adapterFailsTransiently(3);
        when(state.markDeadLettered(eq("n-1"), eq(3), any(), any())).thenReturn(true);
        when(publisher.publishToDlq(any(), any(), any(), anyInt())).thenReturn(PublishOutcome.timeout());

        assertThatThrownBy(() -> processor.process(Channel.EMAIL, emailMessage(), 3, false))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("n-1");
    }

    @Test
    void lostFenceOnDeadLetteredPublishesNothing() {
        adapterFailsTransiently(3);
        when(state.markDeadLettered(eq("n-1"), eq(3), any(), any())).thenReturn(false);

        assertThatCode(() -> processor.process(Channel.EMAIL, emailMessage(), 3, false)).doesNotThrowAnyException();

        verifyNoInteractions(publisher);
    }

    @Test
    void lostFenceOnRetryingPublishesNothing() {
        adapterFailsTransiently(0);
        when(state.markRetrying(eq("n-1"), eq(0), any(), any())).thenReturn(false);

        assertThatCode(() -> processor.process(Channel.EMAIL, emailMessage(), 0, false)).doesNotThrowAnyException();

        verifyNoInteractions(publisher);
    }

    @Test
    void failedRepublishMovesRetryingToDeadLetteredAndCopiesToTheDlq() {
        adapterFailsTransiently(1);
        when(state.markRetrying(eq("n-1"), eq(1), any(), any())).thenReturn(true);
        when(publisher.publishToRetry(any(), any(), anyInt(), any())).thenReturn(PublishOutcome.returned("NO_ROUTE"));
        when(state.markDeadLetteredAfterRepublishFailure(eq("n-1"), eq(1), any())).thenReturn(true);
        when(publisher.publishToDlq(any(), any(), any(), anyInt())).thenReturn(PublishOutcome.acked());

        assertThatCode(() -> processor.process(Channel.EMAIL, emailMessage(), 1, false)).doesNotThrowAnyException();

        verify(publisher).publishToRetry(Channel.EMAIL, emailMessage(), 2, Duration.ofSeconds(25));
        ArgumentCaptor<LastError> lastError = ArgumentCaptor.forClass(LastError.class);
        verify(state).markDeadLetteredAfterRepublishFailure(eq("n-1"), eq(1), lastError.capture());
        assertThat(lastError.getValue().code()).isEqualTo("REPUBLISH_FAILED");
        assertThat(lastError.getValue().message()).contains("republicação para retry.25s falhou");
        assertThat(lastError.getValue().failureClass()).isEqualTo(FailureClass.TRANSIENT);
        verify(publisher).publishToDlq(Channel.EMAIL, emailMessage(), FailureClass.TRANSIENT, 1);
        // transitória nunca vira FAILED
        verify(state, never()).markFailed(anyString(), anyInt(), any(), any());
        verify(state, never()).markDeadLettered(anyString(), anyInt(), any(), any());
    }

    @Test
    void failedRepublishWithUnconfirmedDlqCopyFallsBackToReject() {
        adapterFailsTransiently(0);
        when(state.markRetrying(eq("n-1"), eq(0), any(), any())).thenReturn(true);
        when(publisher.publishToRetry(any(), any(), anyInt(), any())).thenReturn(PublishOutcome.nacked("x"));
        when(state.markDeadLetteredAfterRepublishFailure(eq("n-1"), eq(0), any())).thenReturn(true);
        when(publisher.publishToDlq(any(), any(), any(), anyInt())).thenReturn(PublishOutcome.nacked("y"));

        assertThatThrownBy(() -> processor.process(Channel.EMAIL, emailMessage(), 0, false))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    void failedRepublishWithLostFenceOnRetryingPublishesNothingToTheDlq() {
        adapterFailsTransiently(0);
        when(state.markRetrying(eq("n-1"), eq(0), any(), any())).thenReturn(true);
        when(publisher.publishToRetry(any(), any(), anyInt(), any())).thenReturn(PublishOutcome.timeout());
        when(state.markDeadLetteredAfterRepublishFailure(eq("n-1"), eq(0), any())).thenReturn(false);

        assertThatCode(() -> processor.process(Channel.EMAIL, emailMessage(), 0, false)).doesNotThrowAnyException();

        verify(publisher, never()).publishToDlq(any(), any(), any(), anyInt());
    }

    @Test
    void unknownAdapterExceptionIsMarkedFailedUnknownAndCopiedToTheDlq() {
        claimReturns(claimedEmail(0, 0));
        doThrow(new IllegalStateException("boom")).when(gateway).send(eq(Channel.EMAIL), eq("ana@exemplo.com"), eq(RENDERED), any());
        when(state.markFailed(eq("n-1"), eq(0), any(), any())).thenReturn(true);
        when(publisher.publishToDlq(any(), any(), any(), anyInt())).thenReturn(PublishOutcome.acked());

        assertThatCode(() -> processor.process(Channel.EMAIL, emailMessage(), 0, false)).doesNotThrowAnyException();

        ArgumentCaptor<AttemptEntry> attempt = ArgumentCaptor.forClass(AttemptEntry.class);
        verify(state).markFailed(eq("n-1"), eq(0), attempt.capture(), any(LastError.class));
        assertThat(attempt.getValue().outcome()).isEqualTo(AttemptOutcome.UNKNOWN_FAILURE);
        assertThat(attempt.getValue().failureClass()).isEqualTo(FailureClass.UNKNOWN);
        assertThat(attempt.getValue().errorCode()).isEqualTo("UNEXPECTED");
        verify(publisher).publishToDlq(Channel.EMAIL, emailMessage(), FailureClass.UNKNOWN, 0);
        verify(state, never()).markSent(anyString(), anyInt(), any(), any());
    }

    @Test
    void unconfirmedDlqCopyFallsBackToRejectWithoutRequeue() {
        when(state.claim("n-1", 0, false)).thenReturn(Optional.of(claimedEmail(0, 0)));
        when(catalog.require("welcome")).thenThrow(TemplateNotFoundException.missing("welcome"));
        when(state.markFailed(eq("n-1"), eq(0), any(), any())).thenReturn(true);
        when(publisher.publishToDlq(any(), any(), any(), anyInt())).thenReturn(PublishOutcome.nacked("x"));

        assertThatThrownBy(() -> processor.process(Channel.EMAIL, emailMessage(), 0, false))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("n-1");

        verify(state, times(1)).markFailed(eq("n-1"), eq(0), any(), any());
    }

    @Test
    void lostFenceOnFailedSkipsTheDlqCopyAndAcks() {
        when(state.claim("n-1", 0, false)).thenReturn(Optional.of(claimedEmail(0, 0)));
        when(catalog.require("welcome")).thenThrow(TemplateNotFoundException.missing("welcome"));
        when(state.markFailed(eq("n-1"), eq(0), any(), any())).thenReturn(false);

        assertThatCode(() -> processor.process(Channel.EMAIL, emailMessage(), 0, false)).doesNotThrowAnyException();

        verifyNoInteractions(publisher);
    }

    @Test
    void errorFromTheAdapterPropagatesWithoutBeingClassified() {
        claimReturns(claimedEmail(0, 0));
        doThrow(new AssertionError("erro de JVM")).when(gateway).send(eq(Channel.EMAIL), eq("ana@exemplo.com"), eq(RENDERED), any());

        assertThatThrownBy(() -> processor.process(Channel.EMAIL, emailMessage(), 0, false))
                .isInstanceOf(AssertionError.class);

        verify(state, never()).markFailed(anyString(), anyInt(), any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void attemptNumberFollowsAttemptCountAndRecordsTheRetryLevel() {
        claimReturns(claimedEmail(2, 3));

        processor.process(Channel.EMAIL, emailMessage(), 2, false);

        verify(renderer).render(TEMPLATE, Channel.EMAIL, VARIABLES, "n-1");
        verify(gateway).send(Channel.EMAIL, "ana@exemplo.com", RENDERED, new AttemptContext(2, 4));
        ArgumentCaptor<AttemptEntry> attempt = ArgumentCaptor.forClass(AttemptEntry.class);
        verify(state).markSent(eq("n-1"), eq(2), eq(RENDERED), attempt.capture());
        assertThat(attempt.getValue().number()).isEqualTo(4);
        assertThat(attempt.getValue().retryLevel()).isEqualTo(2);
        assertThat(attempt.getValue().outcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(attempt.getValue().durationMs()).isNotNegative();
        assertThat(attempt.getValue().thread()).isNotBlank();
        assertThat(attempt.getValue().failureClass()).isNull();
    }

    @Test
    void firstDeliveryPassesLevelZeroAndAttemptOneToTheAdapter() {
        claimReturns(claimedEmail(0, 0));

        processor.process(Channel.EMAIL, emailMessage(), 0, false);

        verify(gateway).send(Channel.EMAIL, "ana@exemplo.com", RENDERED, new AttemptContext(0, 1));
    }

    @Test
    void onlyTheDocumentChannelIsSentThroughTheGateway() {
        claimReturns(claimedEmail(0, 0));

        processor.process(Channel.EMAIL, emailMessage(), 0, false);

        verify(gateway).send(eq(Channel.EMAIL), anyString(), any(), any());
        verify(gateway, never()).send(eq(Channel.SMS), anyString(), any(), any());
        verify(gateway, never()).send(eq(Channel.WEBHOOK), anyString(), any(), any());
    }

    // ------------------------------------------------------------ contadores nos pontos de resultado (D-15)

    private double outcome(String outcome, String failureClass) {
        return meters.get("notify.delivery.outcomes")
                .tags("channel", "email", "outcome", outcome, "failure_class", failureClass)
                .counter().count();
    }

    private double retries(int level) {
        return meters.get("notify.delivery.retries")
                .tags("channel", "email", "level", Integer.toString(level))
                .counter().count();
    }

    /** Soma de todos os contadores de resultado e de retry, de todos os canais. */
    private double allResultCounters() {
        return meters.find("notify.delivery.outcomes").counters().stream().mapToDouble(c -> c.count()).sum()
                + meters.find("notify.delivery.retries").counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    void confirmedRetryCountsRetryingAndTheTargetLevel() {
        adapterFailsTransiently(0);
        when(state.markRetrying(eq("n-1"), eq(0), any(), any())).thenReturn(true);
        when(publisher.publishToRetry(any(), any(), anyInt(), any())).thenReturn(PublishOutcome.acked());

        processor.process(Channel.EMAIL, emailMessage(), 0, false);

        assertThat(outcome("retrying", "transient")).isEqualTo(1);
        assertThat(retries(1)).isEqualTo(1);
        assertThat(allResultCounters()).isEqualTo(2);
    }

    @Test
    void retryFromLevelOneCountsTheSecondLevel() {
        adapterFailsTransiently(1);
        when(state.markRetrying(eq("n-1"), eq(1), any(), any())).thenReturn(true);
        when(publisher.publishToRetry(any(), any(), anyInt(), any())).thenReturn(PublishOutcome.acked());

        processor.process(Channel.EMAIL, emailMessage(), 1, false);

        assertThat(retries(2)).isEqualTo(1);
        assertThat(retries(1)).isZero();
    }

    @Test
    void failedRepublishCountsDeadLetteredAndNoRetry() {
        adapterFailsTransiently(0);
        when(state.markRetrying(eq("n-1"), eq(0), any(), any())).thenReturn(true);
        when(publisher.publishToRetry(any(), any(), anyInt(), any())).thenReturn(PublishOutcome.nacked("x"));
        when(state.markDeadLetteredAfterRepublishFailure(eq("n-1"), eq(0), any())).thenReturn(true);
        when(publisher.publishToDlq(any(), any(), any(), anyInt())).thenReturn(PublishOutcome.acked());

        processor.process(Channel.EMAIL, emailMessage(), 0, false);

        assertThat(outcome("retrying", "transient")).isZero();
        assertThat(retries(1)).isZero();
        assertThat(outcome("dead_lettered", "transient")).isEqualTo(1);
    }

    @Test
    void exhaustedLadderCountsDeadLettered() {
        adapterFailsTransiently(3);
        when(state.markDeadLettered(eq("n-1"), eq(3), any(), any())).thenReturn(true);
        when(publisher.publishToDlq(any(), any(), any(), anyInt())).thenReturn(PublishOutcome.acked());

        processor.process(Channel.EMAIL, emailMessage(), 3, false);

        assertThat(outcome("dead_lettered", "transient")).isEqualTo(1);
        assertThat(allResultCounters()).isEqualTo(1);
    }

    @Test
    void permanentFailureCountsFailedPermanent() {
        when(state.claim("n-1", 0, false)).thenReturn(Optional.of(claimedEmail(0, 0)));
        when(catalog.require("welcome")).thenThrow(TemplateNotFoundException.missing("welcome"));
        when(state.markFailed(eq("n-1"), eq(0), any(), any())).thenReturn(true);
        when(publisher.publishToDlq(any(), any(), any(), anyInt())).thenReturn(PublishOutcome.acked());

        processor.process(Channel.EMAIL, emailMessage(), 0, false);

        assertThat(outcome("failed", "permanent")).isEqualTo(1);
        assertThat(allResultCounters()).isEqualTo(1);
    }

    @Test
    void unknownFailureCountsFailedUnknown() {
        claimReturns(claimedEmail(0, 0));
        doThrow(new IllegalStateException("boom")).when(gateway)
                .send(eq(Channel.EMAIL), eq("ana@exemplo.com"), eq(RENDERED), any());
        when(state.markFailed(eq("n-1"), eq(0), any(), any())).thenReturn(true);
        when(publisher.publishToDlq(any(), any(), any(), anyInt())).thenReturn(PublishOutcome.acked());

        processor.process(Channel.EMAIL, emailMessage(), 0, false);

        assertThat(outcome("failed", "unknown")).isEqualTo(1);
        assertThat(outcome("failed", "permanent")).isZero();
    }

    @Test
    void failedIsCountedEvenWhenTheDlqCopyFallsBackToReject() {
        when(state.claim("n-1", 0, false)).thenReturn(Optional.of(claimedEmail(0, 0)));
        when(catalog.require("welcome")).thenThrow(TemplateNotFoundException.missing("welcome"));
        when(state.markFailed(eq("n-1"), eq(0), any(), any())).thenReturn(true);
        when(publisher.publishToDlq(any(), any(), any(), anyInt())).thenReturn(PublishOutcome.nacked("x"));

        assertThatThrownBy(() -> processor.process(Channel.EMAIL, emailMessage(), 0, false))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        // a original chega à DLQ pelo reject: o documento é FAILED do mesmo jeito
        assertThat(outcome("failed", "permanent")).isEqualTo(1);
    }

    @Test
    void lostFenceOnRetryingCountsNothing() {
        adapterFailsTransiently(0);
        when(state.markRetrying(eq("n-1"), eq(0), any(), any())).thenReturn(false);

        processor.process(Channel.EMAIL, emailMessage(), 0, false);

        assertThat(allResultCounters()).isZero();
    }

    @Test
    void lostFenceOnFailedCountsNothing() {
        when(state.claim("n-1", 0, false)).thenReturn(Optional.of(claimedEmail(0, 0)));
        when(catalog.require("welcome")).thenThrow(TemplateNotFoundException.missing("welcome"));
        when(state.markFailed(eq("n-1"), eq(0), any(), any())).thenReturn(false);

        processor.process(Channel.EMAIL, emailMessage(), 0, false);

        assertThat(allResultCounters()).isZero();
    }

    @Test
    void lostFenceOnDeadLetteredCountsNothing() {
        adapterFailsTransiently(3);
        when(state.markDeadLettered(eq("n-1"), eq(3), any(), any())).thenReturn(false);

        processor.process(Channel.EMAIL, emailMessage(), 3, false);

        assertThat(allResultCounters()).isZero();
    }

    @Test
    void lostFenceAfterFailedRepublishCountsNothing() {
        adapterFailsTransiently(0);
        when(state.markRetrying(eq("n-1"), eq(0), any(), any())).thenReturn(true);
        when(publisher.publishToRetry(any(), any(), anyInt(), any())).thenReturn(PublishOutcome.timeout());
        when(state.markDeadLetteredAfterRepublishFailure(eq("n-1"), eq(0), any())).thenReturn(false);

        processor.process(Channel.EMAIL, emailMessage(), 0, false);

        assertThat(allResultCounters()).isZero();
    }

    // ------------------------------------------------------------ recusas de CB e bulkhead (D-05)

    private void gatewayRefusesWith(RuntimeException refusal) {
        claimReturns(claimedEmail(0, 0));
        doThrow(refusal).when(gateway).send(eq(Channel.EMAIL), eq("ana@exemplo.com"), eq(RENDERED), any());
        when(state.markRetrying(eq("n-1"), eq(0), any(), any())).thenReturn(true);
        when(publisher.publishToRetry(any(), any(), anyInt(), any())).thenReturn(PublishOutcome.acked());
    }

    @Test
    void openCircuitBreakerRefusalIsATransientCircuitOpenRetryAndNeverFailed() {
        gatewayRefusesWith(CallNotPermittedException.createCallNotPermittedException(
                CircuitBreaker.ofDefaults("email")));

        assertThatCode(() -> processor.process(Channel.EMAIL, emailMessage(), 0, false)).doesNotThrowAnyException();

        ArgumentCaptor<AttemptEntry> attempt = ArgumentCaptor.forClass(AttemptEntry.class);
        ArgumentCaptor<LastError> lastError = ArgumentCaptor.forClass(LastError.class);
        verify(state).markRetrying(eq("n-1"), eq(0), attempt.capture(), lastError.capture());
        assertThat(attempt.getValue().errorCode()).isEqualTo("CIRCUIT_OPEN");
        assertThat(attempt.getValue().failureClass()).isEqualTo(FailureClass.TRANSIENT);
        assertThat(attempt.getValue().outcome()).isEqualTo(AttemptOutcome.TRANSIENT_FAILURE);
        assertThat(lastError.getValue().code()).isEqualTo("CIRCUIT_OPEN");
        verify(publisher).publishToRetry(Channel.EMAIL, emailMessage(), 1, Duration.ofSeconds(5));
        verify(state, never()).markFailed(anyString(), anyInt(), any(), any());
        verify(publisher, never()).publishToDlq(any(), any(), any(), anyInt());
    }

    @Test
    void fullBulkheadRefusalIsATransientBulkheadFullRetryAndNeverFailed() {
        gatewayRefusesWith(BulkheadFullException.createBulkheadFullException(Bulkhead.ofDefaults("email")));

        assertThatCode(() -> processor.process(Channel.EMAIL, emailMessage(), 0, false)).doesNotThrowAnyException();

        ArgumentCaptor<AttemptEntry> attempt = ArgumentCaptor.forClass(AttemptEntry.class);
        verify(state).markRetrying(eq("n-1"), eq(0), attempt.capture(), any(LastError.class));
        assertThat(attempt.getValue().errorCode()).isEqualTo("BULKHEAD_FULL");
        assertThat(attempt.getValue().failureClass()).isEqualTo(FailureClass.TRANSIENT);
        verify(publisher).publishToRetry(Channel.EMAIL, emailMessage(), 1, Duration.ofSeconds(5));
        verify(state, never()).markFailed(anyString(), anyInt(), any(), any());
    }
}
