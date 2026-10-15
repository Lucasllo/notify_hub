package dev.notifyhub.common.domain;

/**
 * Resultado de uma tentativa de entrega registrada em {@code attempts[]}. A partir da Fase 2 os
 * quatro valores são gravados; cada falha corresponde a uma {@link FailureClass}.
 */
public enum AttemptOutcome {

    /** Entregue pelo adapter. */
    SUCCESS,

    /** Falha {@link FailureClass#TRANSIENT}: segue para o próximo nível de retry ou para a DLQ. */
    TRANSIENT_FAILURE,

    /** Falha {@link FailureClass#PERMANENT}: FAILED sem retry. */
    PERMANENT_FAILURE,

    /** Falha {@link FailureClass#UNKNOWN} (exceção não classificada): FAILED sem retry (D-17). */
    UNKNOWN_FAILURE
}
