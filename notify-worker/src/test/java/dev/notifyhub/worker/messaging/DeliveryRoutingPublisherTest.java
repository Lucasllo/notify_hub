package dev.notifyhub.worker.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.FailureClass;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.worker.messaging.DeliveryRoutingPublisher.PublishOutcome;
import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Mockito puro: como cada resposta do broker vira um {@link PublishOutcome} (D-10). */
class DeliveryRoutingPublisherTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final NotificationMessage SMS_MSG = new NotificationMessage(
            "6f1c2a7e-1b2c-4d5e-8f90-123456789abc", Channel.SMS, "welcome", Instant.now());

    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final DeliveryRoutingPublisher publisher = new DeliveryRoutingPublisher(rabbit, TIMEOUT);

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    private void onSend(Consumer<CorrelationData> broker) {
        doAnswer(invocation -> {
            broker.accept(invocation.getArgument(4));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    private PublishOutcome publishToDlq(DeliveryRoutingPublisher p) {
        return p.publishToDlq(Channel.SMS, SMS_MSG, FailureClass.PERMANENT, 1);
    }

    // ------------------------------------------------------------ confirm

    @Test
    void ackIsOk() {
        onSend(cd -> cd.getFuture().complete(new CorrelationData.Confirm(true, null)));

        PublishOutcome outcome = publishToDlq(publisher);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.reason()).isNull();
    }

    @Test
    void nackIsNotOkAndCarriesTheReason() {
        onSend(cd -> cd.getFuture().complete(new CorrelationData.Confirm(false, "x")));

        PublishOutcome outcome = publishToDlq(publisher);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.reason()).contains("x");
    }

    @Test
    void returnedMessageWinsOverAck() {
        onSend(cd -> {
            Message msg = new Message(new byte[0], new MessageProperties());
            cd.setReturned(new ReturnedMessage(msg, 312, "NO_ROUTE", "notify.dlx", "sms.dlq"));
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        PublishOutcome outcome = publishToDlq(publisher);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.reason()).contains("devolvida").contains("NO_ROUTE");
    }

    @Test
    void confirmThatNeverArrivesIsTimeout() {
        onSend(cd -> { });
        DeliveryRoutingPublisher fast = new DeliveryRoutingPublisher(rabbit, Duration.ofMillis(50));

        PublishOutcome outcome = publishToDlq(fast);

        assertThat(outcome).isEqualTo(PublishOutcome.timeout());
        assertThat(outcome.ok()).isFalse();
    }

    @Test
    void brokerFailureOnSendIsNotOkAndDoesNotPropagate() {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class),
                        any(MessagePostProcessor.class), any(CorrelationData.class));

        PublishOutcome outcome = publishToDlq(publisher);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.reason()).isNotBlank();
    }

    @Test
    void interruptedWaitIsTimeoutAndRestoresTheInterruptFlag() {
        onSend(cd -> { });
        Thread.currentThread().interrupt();

        PublishOutcome outcome = publishToDlq(publisher);

        assertThat(outcome).isEqualTo(PublishOutcome.timeout());
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    // ------------------------------------------------------------ formato da cópia

    @Test
    void everyPublicationUsesAFreshCorrelationIdThatIsNotTheNotificationId() {
        onSend(cd -> cd.getFuture().complete(new CorrelationData.Confirm(true, null)));

        publishToDlq(publisher);
        publishToDlq(publisher);

        ArgumentCaptor<CorrelationData> cd = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbit, times(2)).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class), cd.capture());
        List<CorrelationData> all = cd.getAllValues();
        assertThat(all.get(0).getId()).isNotEqualTo(all.get(1).getId());
        assertThat(all).extracting(CorrelationData::getId).doesNotContain(SMS_MSG.notificationId());
    }

    @Test
    void dlqCopyGoesToTheDeadLetterExchangeWithOnlyTheFailureHeaders() {
        onSend(cd -> cd.getFuture().complete(new CorrelationData.Confirm(true, null)));

        publisher.publishToDlq(Channel.SMS, SMS_MSG, FailureClass.UNKNOWN, 2);

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> mpp = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbit).convertAndSend(eq("notify.dlx"), eq("sms.dlq"), body.capture(), mpp.capture(),
                any(CorrelationData.class));
        assertThat(body.getValue()).isEqualTo(SMS_MSG);

        MessageProperties props = mpp.getValue()
                .postProcessMessage(new Message(new byte[0], new MessageProperties()))
                .getMessageProperties();
        assertThat(props.getMessageId()).isEqualTo(SMS_MSG.notificationId());
        assertThat(props.getHeaders()).containsOnlyKeys("x-failure-class", "notify-retry-count");
        assertThat((Object) props.getHeader("x-failure-class")).isEqualTo("UNKNOWN");
        assertThat((Object) props.getHeader("notify-retry-count")).isEqualTo(2);
    }
}
