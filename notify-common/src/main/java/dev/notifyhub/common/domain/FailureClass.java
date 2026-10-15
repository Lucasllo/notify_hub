package dev.notifyhub.common.domain;

/**
 * Classe de uma falha de entrega. Persistida no Mongo ({@code lastError.failureClass},
 * {@code attempts[].failureClass}), exposta no {@code GET /notifications/{id}} e enviada no header
 * {@code x-failure-class} da cópia publicada na DLQ.
 */
public enum FailureClass {

    /** Pode melhorar sozinha: percorre a escada de retry e, esgotada, termina DEAD_LETTERED. */
    TRANSIENT,

    /** Erro do pedido ou do template: vai direto para FAILED e para a DLQ, sem retry. */
    PERMANENT,

    /**
     * Exceção que o classificador não reconhece (provável bug): FAILED sem gastar retries e DLQ,
     * para o operador distinguir bug de erro do cliente (D-13).
     */
    UNKNOWN
}
