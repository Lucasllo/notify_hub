package dev.notifyhub.common.messaging;

import java.time.Duration;

/**
 * Rótulo legível de um TTL de retry, usado nos nomes das filas de delay e nas routing keys
 * (ex.: {@code notify.email.retry.5s}). O rótulo vem do valor do TTL, nunca de um ordinal,
 * para que mudar os níveis gere filas novas em vez de redeclarar filas existentes com outro TTL.
 */
public final class TtlLabel {

    private TtlLabel() {
    }

    /**
     * Segundos inteiros viram {@code "<n>s"}; o resto vira {@code "<n>ms"}.
     *
     * @throws IllegalArgumentException se a duração for nula, zero ou negativa
     */
    public static String of(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("TTL precisa ser positivo: " + ttl);
        }
        long ms = ttl.toMillis();
        if (ms <= 0) {
            throw new IllegalArgumentException("TTL precisa ter pelo menos 1ms: " + ttl);
        }
        return (ms % 1000 == 0) ? (ms / 1000) + "s" : ms + "ms";
    }
}
