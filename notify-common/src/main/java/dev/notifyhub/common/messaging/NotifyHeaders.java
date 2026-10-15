package dev.notifyhub.common.messaging;

/** Nomes dos headers AMQP próprios do Notify Hub. */
public final class NotifyHeaders {

    /** Quantas vezes a mensagem já passou pelas filas de retry. */
    public static final String RETRY_COUNT = "notify-retry-count";

    /**
     * Classe da falha que encerrou a notificação ({@code TRANSIENT}, {@code PERMANENT} ou
     * {@code UNKNOWN}), gravada pelo worker na cópia que ele publica na DLQ do canal. Uma mensagem
     * que chega à DLQ por reject (fallback) não tem este header: o AMQP 0-9-1 não permite acrescentar
     * headers ao dead-letterar.
     */
    public static final String FAILURE_CLASS = "x-failure-class";

    private NotifyHeaders() {
    }
}
