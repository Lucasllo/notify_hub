package dev.notifyhub.common.domain;

/**
 * Resultado de uma tentativa de entrega registrada em {@code attempts[]}. Na Fase 1 só SUCCESS é
 * gravado; os demais valores já existem para o schema do documento ficar estável.
 */
public enum AttemptOutcome {
    SUCCESS,
    TRANSIENT_FAILURE,
    PERMANENT_FAILURE
}
