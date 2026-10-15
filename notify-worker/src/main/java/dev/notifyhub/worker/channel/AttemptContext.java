package dev.notifyhub.worker.channel;

/**
 * Contexto da tentativa corrente, passado ao adapter.
 *
 * @param retryCount    nível de retry da mensagem (0 = fila principal)
 * @param attemptNumber número da tentativa corrente, a partir de 1
 */
public record AttemptContext(int retryCount, int attemptNumber) {
}
