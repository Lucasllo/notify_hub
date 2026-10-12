package dev.notifyhub.common.messaging;

/** Nomes dos headers AMQP próprios do Notify Hub. */
public final class NotifyHeaders {

    /** Quantas vezes a mensagem já passou pelas filas de retry. */
    public static final String RETRY_COUNT = "notify-retry-count";

    /** Classe da última falha (TRANSIENT/PERMANENT). Reservado para a Fase 2. */
    public static final String FAILURE_CLASS = "x-failure-class";

    private NotifyHeaders() {
    }
}
