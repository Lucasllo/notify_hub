package dev.notifyhub.worker.channel;

/**
 * Contexto da tentativa corrente, passado ao adapter. Existe para as falhas determinísticas dos
 * destinatários mágicos ({@link MagicRecipients}): {@code fail-<N>-then-ok} decide pelo
 * {@code retryCount} e {@code fail-until-reprocess} pelo {@code attemptNumber}.
 *
 * @param retryCount    nível de retry da mensagem (0 = fila principal); o reprocessamento zera
 * @param attemptNumber número da tentativa corrente, a partir de 1 ({@code attemptCount} do
 *                      documento + 1); o reprocessamento não zera
 */
public record AttemptContext(int retryCount, int attemptNumber) {

    public AttemptContext {
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount não pode ser negativo: " + retryCount);
        }
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber deve ser pelo menos 1: " + attemptNumber);
        }
    }
}
