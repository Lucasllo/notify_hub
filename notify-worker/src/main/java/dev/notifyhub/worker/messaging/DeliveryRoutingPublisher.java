package dev.notifyhub.worker.messaging;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.FailureClass;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.messaging.NotifyHeaders;
import dev.notifyhub.common.messaging.NotifyTopology;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publicações do worker dentro do listener, sempre com publisher confirm síncrono (D-10).
 *
 * <ul>
 *   <li>Cada publicação usa {@code new CorrelationData()} (UUID): com {@code mandatory} ligado o id
 *       precisa ser único por publicação pendente, e o {@code notificationId} se repetiria entre
 *       retry e DLQ da mesma notificação (RESEARCH Armadilha 8).</li>
 *   <li>A devolução ({@code getReturned()}) é conferida antes de {@code isAck()}: uma mensagem sem
 *       rota volta como {@link ReturnedMessage} e ainda assim recebe ack do broker.</li>
 *   <li>A espera do confirm tem limite ({@code notify.worker.confirm-timeout}, padrão 5s); timeout
 *       é tratado como falha da publicação (Armadilha 9).</li>
 *   <li>O corpo é o mesmo token {@link NotificationMessage} recebido (sem destinatário nem
 *       variáveis), com propriedades novas: só {@code messageId} e os headers informados, então
 *       nenhum {@code x-death} antigo é copiado.</li>
 * </ul>
 */
@Component
public class DeliveryRoutingPublisher {

    private final RabbitTemplate rabbit;
    private final Duration confirmTimeout;

    public DeliveryRoutingPublisher(RabbitTemplate rabbit,
                                    @Value("${notify.worker.confirm-timeout:5s}") Duration confirmTimeout) {
        this.rabbit = rabbit;
        this.confirmTimeout = confirmTimeout;
    }

    /**
     * Publica uma cópia da mensagem na DLQ do canal: exchange {@link NotifyTopology#DLX_EXCHANGE},
     * routing key {@link Channel#dlqKey()}, headers {@link NotifyHeaders#FAILURE_CLASS} e
     * {@link NotifyHeaders#RETRY_COUNT}.
     */
    public PublishOutcome publishToDlq(Channel channel, NotificationMessage message, FailureClass failureClass,
                                       int retryCount) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(NotifyHeaders.FAILURE_CLASS, failureClass.name());
        headers.put(NotifyHeaders.RETRY_COUNT, retryCount);
        return publish(NotifyTopology.DLX_EXCHANGE, channel.dlqKey(), message, headers);
    }

    /** Esqueleto (RED). */
    public PublishOutcome publishToRetry(Channel channel, NotificationMessage message, int nextRetryCount,
                                         Duration level) {
        throw new UnsupportedOperationException("publishToRetry: plano 02-02");
    }

    private PublishOutcome publish(String exchange, String routingKey, NotificationMessage message,
                                   Map<String, Object> headers) {
        CorrelationData cd = new CorrelationData(); // UUID: nunca o notificationId
        try {
            rabbit.convertAndSend(exchange, routingKey, message, m -> {
                MessageProperties props = m.getMessageProperties();
                props.setMessageId(message.notificationId());
                headers.forEach(props::setHeader);
                return m; // delivery mode PERSISTENT é o padrão
            }, cd);
        } catch (AmqpException e) {
            return PublishOutcome.failed(e.getMessage());
        }
        try {
            CorrelationData.Confirm confirm = cd.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            ReturnedMessage returned = cd.getReturned();
            if (returned != null) {
                return PublishOutcome.returned(returned.getReplyText());
            }
            return confirm.isAck() ? PublishOutcome.acked() : PublishOutcome.nacked(confirm.getReason());
        } catch (TimeoutException e) {
            return PublishOutcome.timeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PublishOutcome.timeout();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return PublishOutcome.nacked(cause.getMessage());
        }
    }

    /**
     * Resultado de uma publicação confirmada.
     *
     * @param ok     {@code true} só com ack do broker e sem devolução
     * @param reason motivo da falha; nulo quando {@code ok}
     */
    public record PublishOutcome(boolean ok, String reason) {

        public static PublishOutcome acked() {
            return new PublishOutcome(true, null);
        }

        public static PublishOutcome nacked(String reason) {
            return new PublishOutcome(false, "nack: " + reason);
        }

        public static PublishOutcome returned(String replyText) {
            return new PublishOutcome(false, "devolvida: " + replyText);
        }

        public static PublishOutcome timeout() {
            return new PublishOutcome(false, "timeout do confirm");
        }

        public static PublishOutcome failed(String reason) {
            return new PublishOutcome(false, "falha ao publicar: " + reason);
        }
    }
}
