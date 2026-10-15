package dev.notifyhub.worker.messaging;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.FailureClass;
import dev.notifyhub.common.messaging.NotificationMessage;
import java.time.Duration;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Esqueleto (RED): a publicação confirmada ainda não existe. */
@Component
public class DeliveryRoutingPublisher {

    private final RabbitTemplate rabbit;
    private final Duration confirmTimeout;

    public DeliveryRoutingPublisher(RabbitTemplate rabbit,
                                    @Value("${notify.worker.confirm-timeout:5s}") Duration confirmTimeout) {
        this.rabbit = rabbit;
        this.confirmTimeout = confirmTimeout;
    }

    public PublishOutcome publishToDlq(Channel channel, NotificationMessage message, FailureClass failureClass,
                                       int retryCount) {
        throw new UnsupportedOperationException("publicação pendente");
    }

    public record PublishOutcome(boolean ok, String reason) {

        public static PublishOutcome acked() {
            return new PublishOutcome(true, null);
        }

        public static PublishOutcome nacked(String reason) {
            return new PublishOutcome(false, reason);
        }
    }
}
