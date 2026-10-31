package dev.notifyhub.api.messaging;

import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.messaging.NotifyHeaders;
import dev.notifyhub.common.messaging.NotifyTopology;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica a mensagem fina da notificação no exchange principal e espera o publisher confirm
 * correlacionado. Com {@code mandatory}, uma mensagem sem rota volta como {@link ReturnedMessage}
 * antes do ack; por isso a devolução é conferida antes de {@code isAck()}.
 */
@Component
public class NotificationPublisher {

    private final RabbitTemplate rabbitTemplate;

    public NotificationPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public PublishResult publish(NotificationMessage msg, Duration timeout) {
        // id único por publicação: necessário para o template associar a devolução (getReturned())
        CorrelationData cd = new CorrelationData(msg.notificationId());
        try {
            rabbitTemplate.convertAndSend(NotifyTopology.MAIN_EXCHANGE, msg.channel().routingKey(), msg, m -> {
                m.getMessageProperties().setMessageId(msg.notificationId());
                m.getMessageProperties().setHeader(NotifyHeaders.RETRY_COUNT, 0);
                return m; // delivery mode PERSISTENT é o padrão
            }, cd);
        } catch (AmqpException e) {
            return PublishResult.brokerUnavailable(e.getMessage());
        }
        try {
            CorrelationData.Confirm confirm = cd.getFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            ReturnedMessage returned = cd.getReturned();
            if (returned != null) {
                return PublishResult.unroutable(returned.getReplyText());
            }
            return confirm.isAck() ? PublishResult.acked() : PublishResult.nacked(confirm.getReason());
        } catch (TimeoutException e) {
            return PublishResult.timeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PublishResult.timeout();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return PublishResult.nacked(cause.getMessage());
        }
    }

    /** Esqueleto (RED). */
    public PublishResult copyToDlqTail(dev.notifyhub.common.domain.Channel channel, byte[] body, String messageId,
            String contentType, java.util.Map<String, Object> headers, Duration timeout) {
        return PublishResult.brokerUnavailable("não implementado");
    }
}
