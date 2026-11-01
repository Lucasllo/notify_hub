package dev.notifyhub.api.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.notifyhub.api.messaging.PublishResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Contrato do contador de resultado do publish da API (D-02, OBS-02), sem Docker: 7 séries
 * pré-registradas com valor 0, as mesmas chaves {@code outcome}/{@code reason} em todas, combinações
 * de conjunto fechado e um incremento por método (T-04-07, T-04-08).
 */
class PublishMetricsTest {

    private static final Set<String> CONTRACT = Set.of(
            "confirmed/none",
            "confirmed/claimed",
            "publish_failed/nack",
            "publish_failed/returned",
            "publish_failed/timeout",
            "publish_failed/broker_unavailable",
            "publish_failed/unconfirmed");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PublishMetrics metrics = new PublishMetrics(registry);

    private List<Counter> outcomes() {
        return List.copyOf(registry.find(PublishMetrics.OUTCOMES).counters());
    }

    /** Valor de cada série, indexado por "outcome/reason". */
    private Map<String, Double> values() {
        return outcomes().stream().collect(Collectors.toMap(
                c -> c.getId().getTag("outcome") + "/" + c.getId().getTag("reason"), Counter::count));
    }

    /** Afirma que só a série {@code key} vale {@code expected} e que as outras 6 continuam em 0. */
    private void assertOnly(String key, double expected) {
        Map<String, Double> values = values();
        assertThat(values).containsEntry(key, expected);
        values.forEach((k, v) -> {
            if (!k.equals(key)) {
                assertThat(v).as(k).isZero();
            }
        });
    }

    // ------------------------------------------------------------ pré-registro (D-02)

    @Test
    void outcomesArePreRegisteredWithSevenZeroSeries() {
        List<Counter> outcomes = outcomes();

        assertThat(PublishMetrics.OUTCOMES).isEqualTo("notify.publish.outcomes");
        assertThat(outcomes).hasSize(7);
        assertThat(outcomes).allSatisfy(c -> assertThat(c.count()).isZero());
    }

    @Test
    void everySeriesHasExactlyTheOutcomeAndReasonTagKeys() {
        // o registry Prometheus descarta meters de mesmo nome com chaves diferentes; id, destinatário,
        // template e texto do broker nunca viram tag (T-04-07)
        assertThat(outcomes()).hasSize(7).allSatisfy(c -> assertThat(
                c.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("outcome", "reason"));
    }

    @Test
    void combinationsAreExactlyTheClosedContract() {
        assertThat(values().keySet()).isEqualTo(CONTRACT);
    }

    // ------------------------------------------------------------ incrementos

    @Test
    void confirmedIncrementsOnlyConfirmedNone() {
        metrics.confirmed();

        assertOnly("confirmed/none", 1.0);
    }

    @Test
    void confirmedByClaimIncrementsOnlyConfirmedClaimed() {
        metrics.confirmedByClaim();

        assertOnly("confirmed/claimed", 1.0);
    }

    @Test
    void failedNackIncrementsOnlyPublishFailedNack() {
        metrics.failed(PublishResult.Outcome.NACK);

        assertOnly("publish_failed/nack", 1.0);
    }

    @Test
    void failedReturnedIncrementsOnlyPublishFailedReturned() {
        metrics.failed(PublishResult.Outcome.RETURNED);

        assertOnly("publish_failed/returned", 1.0);
    }

    @Test
    void failedTimeoutIncrementsOnlyPublishFailedTimeout() {
        metrics.failed(PublishResult.Outcome.TIMEOUT);

        assertOnly("publish_failed/timeout", 1.0);
    }

    @Test
    void failedBrokerUnavailableIncrementsOnlyPublishFailedBrokerUnavailable() {
        metrics.failed(PublishResult.Outcome.BROKER_UNAVAILABLE);

        assertOnly("publish_failed/broker_unavailable", 1.0);
    }

    @Test
    void failedWithAckIsRejected() {
        assertThatThrownBy(() -> metrics.failed(PublishResult.Outcome.ACK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(values().values()).allSatisfy(v -> assertThat(v).isZero());
    }

    @Test
    void unconfirmedAddsTheNumberOfMarkedDocuments() {
        metrics.unconfirmed(3);

        assertOnly("publish_failed/unconfirmed", 3.0);
    }

    @Test
    void unconfirmedWithZeroChangesNothing() {
        metrics.unconfirmed(0);

        assertThat(outcomes()).hasSize(7);
        assertThat(values().values()).allSatisfy(v -> assertThat(v).isZero());
    }

    @Test
    void unconfirmedWithANegativeCountIsRejected() {
        assertThatThrownBy(() -> metrics.unconfirmed(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(values().values()).allSatisfy(v -> assertThat(v).isZero());
    }
}
