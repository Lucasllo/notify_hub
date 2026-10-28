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

    /**
     * Publica o token do próximo nível na fila de delay do canal (D-11, passo 2): exchange
     * {@link NotifyTopology#RETRY_EXCHANGE}, routing key {@link Channel#retryKey(Duration)} e header
     * {@link NotifyHeaders#RETRY_COUNT} = {@code nextRetryCount}.
     *
     * <p>A routing key sai do mesmo {@code Duration} de {@code TopologyProperties.retryLevels()} que
     * declarou a fila, então o rótulo ({@code 5s}, {@code 200ms}) bate com o nome da fila por
     * construção; nunca é montada à mão.
     */
    public PublishOutcome publishToRetry(Channel channel, NotificationMessage message, int nextRetryCount,
                                         Duration level) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(NotifyHeaders.RETRY_COUNT, nextRetryCount);
        return publish(NotifyTopology.RETRY_EXCHANGE, channel.retryKey(level), message, headers);
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
            return PublishOutcome.sendFailed(e);
        }
        try {
            CorrelationData.Confirm confirm = cd.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            ReturnedMessage returned = cd.getReturned();
            if (returned != null) {
                return PublishOutcome.returned(returned.getReplyCode());
            }
            return confirm.isAck() ? PublishOutcome.acked() : PublishOutcome.nacked();
        } catch (TimeoutException e) {
            return PublishOutcome.timeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PublishOutcome.timeout();
        } catch (ExecutionException e) {
            return PublishOutcome.confirmFailed(e.getCause() != null ? e.getCause() : e);
        }
    }

    /**
     * Resultado de uma publicação confirmada.
     *
     * <p>O motivo vem de um conjunto fechado e entra no {@code lastError} REPUBLISH_FAILED gravado no
     * Mongo: o texto do nack, o reply text da devolução e a mensagem de exceção nunca são lidos, porque
     * podem trazer destinatário ou outro texto livre (T-02-01, WR-05).
     *
     * @param ok     {@code true} só com ack do broker e sem devolução
     * @param reason {@code NACK}, {@code RETURNED:<replyCode>}, {@code CONFIRM_TIMEOUT},
     *               {@code SEND_FAILED:<tipo>} ou {@code CONFIRM_FAILED:<tipo>}, onde {@code <tipo>} é
     *               o nome simples da classe da exceção; nulo quando {@code ok}; nunca contém texto
     *               livre do broker ou de exceção (T-02-01)
     */
    public record PublishOutcome(boolean ok, String reason) {

        public static PublishOutcome acked() {
            return new PublishOutcome(true, null);
        }

        /** Nack do broker; o motivo do nack é descartado. */
        public static PublishOutcome nacked() {
            return new PublishOutcome(false, "NACK");
        }

        /** Devolução ({@code mandatory}); só o reply code numérico, nunca o reply text. */
        public static PublishOutcome returned(int replyCode) {
            return new PublishOutcome(false, "RETURNED:" + replyCode);
        }

        public static PublishOutcome timeout() {
            return new PublishOutcome(false, "CONFIRM_TIMEOUT");
        }

        /** Exceção no envio; só o tipo da exceção. */
        public static PublishOutcome sendFailed(Throwable cause) {
            return new PublishOutcome(false, "SEND_FAILED:" + typeOf(cause));
        }

        /** Future do confirm completado com exceção; só o tipo da exceção. */
        public static PublishOutcome confirmFailed(Throwable cause) {
            return new PublishOutcome(false, "CONFIRM_FAILED:" + typeOf(cause));
        }

        private static String typeOf(Throwable t) {
            String simple = t.getClass().getSimpleName();
            return simple.isEmpty() ? t.getClass().getName() : simple; // classe anônima
        }
    }
}
