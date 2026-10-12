package dev.notifyhub.worker.listener;

import dev.notifyhub.common.messaging.NotifyHeaders;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

/**
 * Lê o header {@link NotifyHeaders#RETRY_COUNT} (nível de retry da mensagem). O contador é da
 * aplicação, nunca do {@code x-death}.
 *
 * <p>Aceita só inteiros ≥ 0: header ausente vira 0, número vale pelo valor e string numérica vale
 * depois do trim (o painel do RabbitMQ publica headers como string). Qualquer outra coisa rejeita a
 * mensagem sem requeue, e ela estaciona na DLQ do canal (T-01-24).
 */
public final class RetryHeaders {

    private RetryHeaders() {
    }

    /**
     * @throws AmqpRejectAndDontRequeueException se o valor for negativo, fracionário, maior que
     *                                           {@code Integer.MAX_VALUE} ou não numérico
     */
    public static int parse(Object header) {
        if (header == null) {
            return 0;
        }
        long value;
        if (header instanceof Number number) {
            value = number.longValue();
            if (number.doubleValue() != value) {
                throw invalid(header);
            }
        } else if (header instanceof CharSequence text) {
            try {
                value = Long.parseLong(text.toString().trim());
            } catch (NumberFormatException e) {
                throw invalid(header);
            }
        } else {
            throw invalid(header);
        }
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw invalid(header);
        }
        return (int) value;
    }

    private static AmqpRejectAndDontRequeueException invalid(Object header) {
        return new AmqpRejectAndDontRequeueException(
                "header " + NotifyHeaders.RETRY_COUNT + " inválido: " + header);
    }
}
