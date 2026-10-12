package dev.notifyhub.api.messaging;

/**
 * Resultado de uma publicação com confirm.
 *
 * @param outcome o que o broker respondeu (ou deixou de responder)
 * @param detail  detalhe legível; nulo em ACK e TIMEOUT
 */
public record PublishResult(Outcome outcome, String detail) {

    /** Desfechos possíveis de uma publicação. */
    public enum Outcome {
        ACK,
        NACK,
        RETURNED,
        TIMEOUT,
        BROKER_UNAVAILABLE
    }

    /** Broker confirmou (ack) e não devolveu a mensagem. */
    public static PublishResult acked() {
        return new PublishResult(Outcome.ACK, null);
    }

    public static PublishResult nacked(String reason) {
        return new PublishResult(Outcome.NACK, reason);
    }

    public static PublishResult unroutable(String replyText) {
        return new PublishResult(Outcome.RETURNED, replyText);
    }

    public static PublishResult timeout() {
        return new PublishResult(Outcome.TIMEOUT, null);
    }

    public static PublishResult brokerUnavailable(String message) {
        return new PublishResult(Outcome.BROKER_UNAVAILABLE, message);
    }

    /** {@code true} só quando o broker confirmou e não devolveu a mensagem. */
    public boolean ok() {
        return outcome == Outcome.ACK;
    }

    /** Código gravado em {@code lastError.code}; nulo para ACK. */
    public String errorCode() {
        return switch (outcome) {
            case ACK -> null;
            case NACK -> "PUBLISH_NACK";
            case RETURNED -> "PUBLISH_RETURNED";
            case TIMEOUT -> "PUBLISH_TIMEOUT";
            case BROKER_UNAVAILABLE -> "BROKER_UNAVAILABLE";
        };
    }
}
