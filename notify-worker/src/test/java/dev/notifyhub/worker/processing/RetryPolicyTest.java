package dev.notifyhub.worker.processing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.notifyhub.worker.processing.RetryPolicy.RetryDecision;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Decisão pura nível atual → próximo nível ou esgotado, dos dois lados de cada fronteira (DLV-04). */
class RetryPolicyTest {

    private static final List<Duration> THREE = List.of(
            Duration.ofSeconds(5), Duration.ofSeconds(25), Duration.ofSeconds(125));

    private static void assertNext(RetryDecision decision, int nextRetryCount, Duration nextLevel) {
        assertThat(decision.exhausted()).isFalse();
        assertThat(decision.nextRetryCount()).isEqualTo(nextRetryCount);
        assertThat(decision.nextLevel()).isEqualTo(nextLevel);
    }

    private static void assertExhausted(RetryDecision decision) {
        assertThat(decision.exhausted()).isTrue();
        assertThat(decision.nextLevel()).isNull();
    }

    @Test
    void mainQueueFailureGoesToTheFirstLevel() {
        assertNext(RetryPolicy.decide(0, THREE), 1, Duration.ofSeconds(5));
    }

    @Test
    void firstLevelFailureGoesToTheSecondLevel() {
        assertNext(RetryPolicy.decide(1, THREE), 2, Duration.ofSeconds(25));
    }

    @Test
    void secondLevelFailureGoesToTheLastLevel() {
        assertNext(RetryPolicy.decide(2, THREE), 3, Duration.ofSeconds(125));
    }

    @Test
    void lastLevelFailureIsExhausted() {
        assertExhausted(RetryPolicy.decide(3, THREE));
    }

    @Test
    void tamperedLevelAboveTheLadderIsExhausted() {
        assertExhausted(RetryPolicy.decide(7, THREE));
    }

    @Test
    void singleLevelLadderHasOneRetry() {
        List<Duration> one = List.of(Duration.ofSeconds(1));

        assertNext(RetryPolicy.decide(0, one), 1, Duration.ofSeconds(1));
        assertExhausted(RetryPolicy.decide(1, one));
    }

    @Test
    void twoLevelLadderInMillisecondsUsesTheSameDurations() {
        List<Duration> two = List.of(Duration.ofMillis(200), Duration.ofMillis(500));

        assertNext(RetryPolicy.decide(1, two), 2, Duration.ofMillis(500));
        assertExhausted(RetryPolicy.decide(2, two));
    }
}
