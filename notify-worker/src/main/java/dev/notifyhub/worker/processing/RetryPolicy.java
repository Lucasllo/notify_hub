package dev.notifyhub.worker.processing;

import java.time.Duration;
import java.util.List;

/** Esqueleto (RED): sempre esgotado. */
public final class RetryPolicy {

    private RetryPolicy() {
    }

    public static RetryDecision decide(int retryCount, List<Duration> levels) {
        return new RetryDecision(true, retryCount, null);
    }

    public record RetryDecision(boolean exhausted, int nextRetryCount, Duration nextLevel) {
    }
}
