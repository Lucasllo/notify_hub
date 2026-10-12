package dev.notifyhub.api.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import dev.notifyhub.api.messaging.PublishResult.Outcome;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.messaging.NotificationMessage;
import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Mockito puro: como cada resposta do broker vira um {@link PublishResult}. */
class NotificationPublisherTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final NotificationMessage MSG = new NotificationMessage(
            "6f1c2a7e-1b2c-4d5e-8f90-123456789abc", Channel.EMAIL, "welcome", Instant.now());

    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final NotificationPublisher publisher = new NotificationPublisher(rabbit);

    private void onSend(Consumer<CorrelationData> broker) {
        doAnswer(invocation -> {
            broker.accept(invocation.getArgument(4));
            return null;
        }).when(rabbit).convertAndSend(any(String.class), any(String.class), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void ackIsOk() {
        onSend(cd -> cd.getFuture().complete(new CorrelationData.Confirm(true, null)));

        PublishResult result = publisher.publish(MSG, TIMEOUT);

        assertThat(result.outcome()).isEqualTo(Outcome.ACK);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void nackCarriesReason() {
        onSend(cd -> cd.getFuture().complete(new CorrelationData.Confirm(false, "x")));

        PublishResult result = publisher.publish(MSG, TIMEOUT);

        assertThat(result.outcome()).isEqualTo(Outcome.NACK);
        assertThat(result.detail()).isEqualTo("x");
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PUBLISH_NACK");
    }

    @Test
    void returnedMessageWinsOverAck() {
        onSend(cd -> {
            Message msg = new Message(new byte[0], new MessageProperties());
            cd.setReturned(new ReturnedMessage(msg, 312, "NO_ROUTE", "notify.exchange", "notification.email"));
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        PublishResult result = publisher.publish(MSG, TIMEOUT);

        assertThat(result.outcome()).isEqualTo(Outcome.RETURNED);
        assertThat(result.detail()).isEqualTo("NO_ROUTE");
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PUBLISH_RETURNED");
    }

    @Test
    void confirmThatNeverArrivesIsTimeout() {
        onSend(cd -> { });

        PublishResult result = publisher.publish(MSG, Duration.ofMillis(50));

        assertThat(result.outcome()).isEqualTo(Outcome.TIMEOUT);
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PUBLISH_TIMEOUT");
    }

    @Test
    void brokerDownIsBrokerUnavailable() {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbit).convertAndSend(any(String.class), any(String.class), any(Object.class),
                        any(MessagePostProcessor.class), any(CorrelationData.class));

        PublishResult result = publisher.publish(MSG, TIMEOUT);

        assertThat(result.outcome()).isEqualTo(Outcome.BROKER_UNAVAILABLE);
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("BROKER_UNAVAILABLE");
    }
}
