package dev.notifyhub.api.messaging;

import com.rabbitmq.client.LongString;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.messaging.NotifyHeaders;
import dev.notifyhub.common.messaging.NotifyTopology;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica a mensagem fina da notificação no exchange principal e espera o publisher confirm
 * correlacionado. Com {@code mandatory}, uma mensagem sem rota volta como {@link ReturnedMessage}
 * antes do ack; por isso a devolução é conferida antes de {@code isAck()}.
 *
 * <p>Também copia uma mensagem da DLQ para o fim da própria DLQ ({@link #copyToDlqTail}), usado pelo
 * reprocessamento quando a mensagem não pode voltar à fila principal (D-02).
 */
@Component
public class NotificationPublisher {

    /** Headers que a cópia para a DLQ preserva; {@code x-death} e {@code __TypeId__} ficam de fora. */
    private static final List<String> DLQ_COPY_HEADERS = List.of(NotifyHeaders.FAILURE_CLASS, NotifyHeaders.RETRY_COUNT);

    private final RabbitTemplate rabbitTemplate;

    public NotificationPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public PublishResult publish(NotificationMessage msg, Duration timeout) {
        // Correlação com UUID, nunca o id da notificação: o reprocessamento e a republicação publicam o
        // mesmo id de novo, e ids repetidos em voo confundiriam a associação da devolução (Armadilha 7)
        CorrelationData cd = new CorrelationData();
        try {
            rabbitTemplate.convertAndSend(NotifyTopology.MAIN_EXCHANGE, msg.channel().routingKey(), msg, m -> {
                m.getMessageProperties().setMessageId(msg.notificationId());
                m.getMessageProperties().setHeader(NotifyHeaders.RETRY_COUNT, 0);
                return m; // delivery mode PERSISTENT é o padrão
            }, cd);
        } catch (AmqpException e) {
            return PublishResult.brokerUnavailable(e.getMessage());
        }
        return awaitConfirm(cd, timeout);
    }

    /**
     * Publica os bytes originais de uma mensagem da DLQ no fim da DLQ do canal
     * ({@link NotifyTopology#DLX_EXCHANGE}, routing key {@link Channel#dlqKey()}), com confirm.
     *
     * <p>Copia só {@code messageId}, content-type e os headers {@link NotifyHeaders#FAILURE_CLASS} e
     * {@link NotifyHeaders#RETRY_COUNT} quando presentes ({@code LongString} vira {@code String}). O
     * {@code x-death} da entrega não é copiado.
     *
     * @param channel     canal da DLQ
     * @param body        bytes originais da mensagem
     * @param messageId   {@code messageId} da entrega (pode ser nulo)
     * @param contentType content-type da entrega; nulo vira {@code application/json}
     * @param headers     headers da entrega (pode ser nulo)
     * @param timeout     espera máxima pelo confirm
     */
    public PublishResult copyToDlqTail(Channel channel, byte[] body, String messageId, String contentType,
            Map<String, Object> headers, Duration timeout) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(messageId);
        props.setContentType(contentType != null ? contentType : MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        if (headers != null) {
            for (String name : DLQ_COPY_HEADERS) {
                Object value = headers.get(name);
                if (value != null) {
                    props.setHeader(name, value instanceof LongString ? value.toString() : value);
                }
            }
        }
        CorrelationData cd = new CorrelationData();
        try {
            rabbitTemplate.send(NotifyTopology.DLX_EXCHANGE, channel.dlqKey(), new Message(body, props), cd);
        } catch (AmqpException e) {
            return PublishResult.brokerUnavailable(e.getMessage());
        }
        return awaitConfirm(cd, timeout);
    }

    private static PublishResult awaitConfirm(CorrelationData cd, Duration timeout) {
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
}
