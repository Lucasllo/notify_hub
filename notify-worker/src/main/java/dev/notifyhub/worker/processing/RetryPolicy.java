package dev.notifyhub.worker.processing;

import java.time.Duration;
import java.util.List;

/**
 * Escada de retry como função pura (DLV-04): dado o nível em que a tentativa rodou, diz qual é o
 * próximo nível ou se a escada acabou.
 *
 * <p>O nível vem do header {@code notify-retry-count}, mantido pela aplicação; {@code x-death} não
 * é usado. Com N níveis, {@code h = 0..N-1} seguem para o nível {@code h+1} e {@code h ≥ N}
 * (inclusive um header adulterado acima da escada) é esgotado. Com 3 níveis, uma notificação que
 * sempre falha soma 4 tentativas: a inicial e uma por nível.
 *
 * <p>Fronteira: o header aceita até {@code 2147483647} ({@code Integer.MAX_VALUE}), que o painel do
 * RabbitMQ pode publicar (T-01-24). Por isso a escada esgotada ({@code retryCount >= levels.size()})
 * é testada <b>antes</b> do {@code +1}: somar primeiro estouraria o {@code int} e o índice da escada
 * lançaria fora do tratamento de falha (WR-02 do 02-REVIEW).
 */
public final class RetryPolicy {

    private RetryPolicy() {
    }

    /**
     * @param retryCount nível em que a tentativa rodou (0 = fila principal)
     * @param levels     níveis de {@code notify.topology.retry-levels}, os mesmos {@code Duration}
     *                   que declararam as filas de delay
     */
    public static RetryDecision decide(int retryCount, List<Duration> levels) {
        if (retryCount >= levels.size()) {
            return new RetryDecision(true, retryCount, null);
        }
        // aqui retryCount < levels.size(): a soma nunca estoura
        int next = retryCount + 1;
        return new RetryDecision(false, next, levels.get(next - 1));
    }

    /**
     * @param exhausted      {@code true} quando não há próximo nível (DEAD_LETTERED)
     * @param nextRetryCount valor do header no token do próximo nível; quando esgotado, repete o
     *                       nível atual
     * @param nextLevel      TTL do próximo nível; nulo quando esgotado
     */
    public record RetryDecision(boolean exhausted, int nextRetryCount, Duration nextLevel) {
    }
}
