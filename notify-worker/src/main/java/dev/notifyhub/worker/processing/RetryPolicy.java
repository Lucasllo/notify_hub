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
        int next = retryCount + 1;
        if (next <= levels.size()) {
            return new RetryDecision(false, next, levels.get(next - 1));
        }
        return new RetryDecision(true, retryCount, null);
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
