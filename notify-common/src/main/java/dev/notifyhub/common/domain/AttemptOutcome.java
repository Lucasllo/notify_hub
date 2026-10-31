package dev.notifyhub.common.domain;

/**
 * Resultado de uma tentativa de entrega registrada em {@code attempts[]}. A partir da Fase 2 os
 * quatro resultados do adapter são gravados; cada falha corresponde a uma {@link FailureClass}. A
 * partir da Fase 3, o histórico também registra o evento {@link #REPROCESSED}, que não é tentativa de
 * entrega.
 */
public enum AttemptOutcome {

    /** Entregue pelo adapter. */
    SUCCESS,

    /** Falha {@link FailureClass#TRANSIENT}: segue para o próximo nível de retry ou para a DLQ. */
    TRANSIENT_FAILURE,

    /** Falha {@link FailureClass#PERMANENT}: FAILED sem retry. */
    PERMANENT_FAILURE,

    /** Falha {@link FailureClass#UNKNOWN} (exceção não classificada): FAILED sem retry (D-17). */
    UNKNOWN_FAILURE,

    /**
     * Evento do reprocessamento da DLQ pelo operador (ADM-02). Não é tentativa de entrega: a entrada
     * tem {@code number} 0, duração 0 e nenhuma {@link FailureClass}, e não é resultado do adapter.
     */
    REPROCESSED
}
