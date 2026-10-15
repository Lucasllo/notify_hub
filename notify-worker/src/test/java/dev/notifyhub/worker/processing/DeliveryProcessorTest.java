package dev.notifyhub.worker.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import dev.notifyhub.common.domain.AttemptOutcome;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.FailureClass;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.persistence.AttemptEntry;
import dev.notifyhub.common.persistence.LastError;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.common.persistence.RenderedContent;
import dev.notifyhub.worker.channel.ChannelAdapter;
import dev.notifyhub.worker.channel.SimulatedDeliveryException;
import dev.notifyhub.worker.failure.FailureClassifier;
import dev.notifyhub.worker.messaging.DeliveryRoutingPublisher;
import dev.notifyhub.worker.messaging.DeliveryRoutingPublisher.PublishOutcome;
import dev.notifyhub.worker.template.TemplateCatalog;
import dev.notifyhub.worker.template.TemplateDocument;
import dev.notifyhub.worker.template.TemplateNotFoundException;
import dev.notifyhub.worker.template.TemplateRenderer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

/** Orquestração do processador: duplicatas, canal errado, erros sobem e número da tentativa. */
class DeliveryProcessorTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final Map<String, Object> VARIABLES = Map.of("name", "Ana");
    private static final TemplateDocument TEMPLATE = new TemplateDocument("welcome", 1, List.of("name"), Map.of());
    private static final RenderedContent RENDERED = new RenderedContent("Olá, Ana", "<p>Olá, Ana</p>");

    private final NotificationStateService state = mock(NotificationStateService.class);
    private final TemplateCatalog catalog = mock(TemplateCatalog.class);
    private final TemplateRenderer renderer = mock(TemplateRenderer.class);
    private final ChannelAdapter email = adapter(Channel.EMAIL);
    private final ChannelAdapter sms = adapter(Channel.SMS);
    private final ChannelAdapter webhook = adapter(Channel.WEBHOOK);
    private final FailureClassifier classifier = new FailureClassifier();
    private final DeliveryRoutingPublisher publisher = mock(DeliveryRoutingPublisher.class);
    private final DeliveryProcessor processor =
            new DeliveryProcessor(state, catalog, renderer, List.of(email, sms, webhook), classifier, publisher);

    private static ChannelAdapter adapter(Channel channel) {
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        when(adapter.channel()).thenReturn(channel);
        return adapter;
    }

    private static NotificationMessage emailMessage() {
        return new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW);
    }

    private static NotificationDocument claimedEmail(int retryCount, int attemptCount) {
        return new NotificationDocument("n-1", Channel.EMAIL, "ana@exemplo.com", "welcome", VARIABLES,
                NotificationStatus.PROCESSING, retryCount, attemptCount, null, null, List.of(),
                NOW, NOW, NOW, NOW, null);
    }

    private void claimReturns(NotificationDocument doc) {
        when(state.claim("n-1", doc.retryCount())).thenReturn(Optional.of(doc));
        when(catalog.require("welcome")).thenReturn(TEMPLATE);
        when(renderer.render(TEMPLATE, Channel.EMAIL, VARIABLES, "n-1")).thenReturn(RENDERED);
        when(state.markSent(eq("n-1"), anyInt(), any(), any())).thenReturn(true);
    }

    @Test
    void duplicateOrStaleMessageIsAckedWithoutSending() {
        when(state.claim("n-1", 0)).thenReturn(Optional.empty());

        assertThatCode(() -> processor.process(Channel.EMAIL, emailMessage(), 0)).doesNotThrowAnyException();

        verifyNoInteractions(catalog, renderer);
        verify(email, never()).send(anyString(), any());
        verify(state, never()).markSent(anyString(), anyInt(), any(), any());
    }

    @Test
    void messageFromAnotherChannelIsRejectedWithoutClaiming() {
        assertThatThrownBy(() -> processor.process(Channel.SMS, emailMessage(), 0))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(state, never()).claim(anyString(), anyInt());
    }

    @Test
    void missingTemplateIsMarkedFailedAndCopiedToTheDlqWithoutThrowing() {
        when(state.claim("n-1", 0)).thenReturn(Optional.of(claimedEmail(0, 0)));
        when(catalog.require("welcome")).thenThrow(TemplateNotFoundException.missing("welcome"));
        when(state.markFailed(eq("n-1"), eq(0), any(), any())).thenReturn(true);
        when(publisher.publishToDlq(any(), any(), any(), anyInt())).thenReturn(PublishOutcome.acked());

        assertThatCode(() -> processor.process(Channel.EMAIL, emailMessage(), 0)).doesNotThrowAnyException();

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
        verify(email, never()).send(anyString(), any());
        verify(state, never()).markSent(anyString(), anyInt(), any(), any());
    }

    @Test
    void transientAdapterFailureStillPropagatesAndNothingIsWritten() {
        claimReturns(claimedEmail(0, 0));
        doThrow(new SimulatedDeliveryException("falha simulada no canal email"))
                .when(email).send("ana@exemplo.com", RENDERED);

        assertThatThrownBy(() -> processor.process(Channel.EMAIL, emailMessage(), 0))
                .isInstanceOf(SimulatedDeliveryException.class);

        verify(state, never()).markSent(anyString(), anyInt(), any(), any());
        verify(state, never()).markFailed(anyString(), anyInt(), any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void unknownAdapterExceptionIsMarkedFailedUnknownAndCopiedToTheDlq() {
        claimReturns(claimedEmail(0, 0));
        doThrow(new IllegalStateException("boom")).when(email).send("ana@exemplo.com", RENDERED);
        when(state.markFailed(eq("n-1"), eq(0), any(), any())).thenReturn(true);
        when(publisher.publishToDlq(any(), any(), any(), anyInt())).thenReturn(PublishOutcome.acked());

        assertThatCode(() -> processor.process(Channel.EMAIL, emailMessage(), 0)).doesNotThrowAnyException();

        ArgumentCaptor<AttemptEntry> attempt = ArgumentCaptor.forClass(AttemptEntry.class);
        verify(state).markFailed(eq("n-1"), eq(0), attempt.capture(), any(LastError.class));
        assertThat(attempt.getValue().outcome()).isEqualTo(AttemptOutcome.UNKNOWN_FAILURE);
        assertThat(attempt.getValue().failureClass()).isEqualTo(FailureClass.UNKNOWN);
        assertThat(attempt.getValue().errorCode()).isEqualTo("UNEXPECTED");
        verify(publisher).publishToDlq(Channel.EMAIL, emailMessage(), FailureClass.UNKNOWN, 0);
        verify(state, never()).markSent(anyString(), anyInt(), any(), any());
    }

    @Test
    void errorFromTheAdapterPropagatesWithoutBeingClassified() {
        claimReturns(claimedEmail(0, 0));
        doThrow(new AssertionError("erro de JVM")).when(email).send("ana@exemplo.com", RENDERED);

        assertThatThrownBy(() -> processor.process(Channel.EMAIL, emailMessage(), 0))
                .isInstanceOf(AssertionError.class);

        verify(state, never()).markFailed(anyString(), anyInt(), any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void attemptNumberFollowsAttemptCountAndRecordsTheRetryLevel() {
        claimReturns(claimedEmail(2, 3));

        processor.process(Channel.EMAIL, emailMessage(), 2);

        verify(renderer).render(TEMPLATE, Channel.EMAIL, VARIABLES, "n-1");
        verify(email).send("ana@exemplo.com", RENDERED);
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
    void onlyTheAdapterOfTheDocumentChannelIsCalled() {
        claimReturns(claimedEmail(0, 0));

        processor.process(Channel.EMAIL, emailMessage(), 0);

        verify(sms, never()).send(anyString(), any());
        verify(webhook, never()).send(anyString(), any());
    }

    @Test
    void processorRefusesToStartWithoutAnAdapterPerChannel() {
        assertThatThrownBy(() -> new DeliveryProcessor(state, catalog, renderer, List.of(email, sms),
                classifier, publisher))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEBHOOK");
        assertThatThrownBy(() -> new DeliveryProcessor(state, catalog, renderer,
                List.of(email, sms, webhook, email), classifier, publisher))
                .isInstanceOf(IllegalStateException.class);
    }
}
