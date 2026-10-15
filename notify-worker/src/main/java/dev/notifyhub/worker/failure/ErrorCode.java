package dev.notifyhub.worker.failure;

/**
 * Conjunto fechado de códigos de erro de entrega (D-14). É gravado com {@link #name()} em
 * {@code AttemptEntry.errorCode} e {@code LastError.code}. Nunca vira tag de métrica: a
 * cardinalidade das métricas fica nas classes de falha, não nos códigos.
 */
public enum ErrorCode {

    /** Falha transitória do adapter simulado (taxa de falha configurada ou envio interrompido). */
    SIMULATED_TRANSIENT,

    /** Falha permanente forçada pelo adapter simulado (destinatário mágico). */
    SIMULATED_PERMANENT,

    /** O circuit breaker do canal estava aberto e recusou a chamada. */
    CIRCUIT_OPEN,

    /** O bulkhead do canal estava cheio e recusou a chamada. */
    BULKHEAD_FULL,

    /** O MongoDB não respondeu durante o processamento (timeout, socket, recurso indisponível). */
    STORE_UNAVAILABLE,

    /** O template não existe na coleção ou não tem variante para o canal. */
    TEMPLATE_NOT_FOUND,

    /** O template existe, mas não compila (erro de sintaxe Mustache). */
    TEMPLATE_INVALID,

    /** Falta no contexto uma variável que o template usa. */
    MISSING_VARIABLE,

    /** O corpo renderizado do webhook não é um JSON válido. */
    INVALID_WEBHOOK_PAYLOAD,

    /** A republicação para a fila de delay falhou e a notificação foi encerrada. */
    REPUBLISH_FAILED,

    /** Exceção que o classificador não reconhece: provável bug, vai para FAILED sem retry. */
    UNEXPECTED
}
