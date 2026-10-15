package dev.notifyhub.worker.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.FailureClass;
import dev.notifyhub.common.messaging.TopologyProperties;
import dev.notifyhub.worker.channel.SimulatedDeliveryException;
import dev.notifyhub.worker.failure.ErrorCode;
import dev.notifyhub.worker.failure.FailureClassifier;
import dev.notifyhub.worker.failure.PermanentDeliveryException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Contrato das métricas do D-15, sem Docker: séries pré-registradas com valor 0, tags de conjunto
 * fechado com as mesmas chaves por nome e os nomes que o Prometheus coleta (OBS-01, T-02-24).
 */
class DeliveryMetricsTest {

    private static final TopologyProperties THREE_LEVELS = new TopologyProperties(List.of(
            Duration.ofSeconds(5), Duration.ofSeconds(25), Duration.ofSeconds(125)));
    private static final TopologyProperties TWO_LEVELS = new TopologyProperties(List.of(
            Duration.ofSeconds(1), Duration.ofSeconds(5)));

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private DeliveryMetrics metrics(TopologyProperties topology) {
        return new DeliveryMetrics(registry, topology, new FailureClassifier());
    }

    // ------------------------------------------------------------ pré-registro (D-15)

    @Test
    void outcomesArePreRegisteredWithFifteenZeroSeries() {
        metrics(THREE_LEVELS);

        List<Counter> outcomes = List.copyOf(registry.find("notify.delivery.outcomes").counters());
        assertThat(outcomes).hasSize(15);
        assertThat(outcomes).allSatisfy(c -> assertThat(c.count()).isZero());
        Set<String> combinations = outcomes.stream()
                .map(c -> c.getId().getTag("channel") + "/" + c.getId().getTag("outcome") + "/"
                        + c.getId().getTag("failure_class"))
                .collect(Collectors.toSet());
        for (Channel channel : Channel.values()) {
            assertThat(combinations).contains(
                    channel.id() + "/sent/none",
                    channel.id() + "/retrying/transient",
                    channel.id() + "/dead_lettered/transient",
                    channel.id() + "/failed/permanent",
                    channel.id() + "/failed/unknown");
        }
    }

    @Test
    void retriesArePreRegisteredOnePerChannelAndLevel() {
        metrics(THREE_LEVELS);

        List<Counter> retries = List.copyOf(registry.find("notify.delivery.retries").counters());
        assertThat(retries).hasSize(9);
        assertThat(retries).allSatisfy(c -> assertThat(c.count()).isZero());
        assertThat(retries).extracting(c -> c.getId().getTag("level")).containsOnly("1", "2", "3");
    }

    @Test
    void retriesFollowTheNumberOfConfiguredLevels() {
        metrics(TWO_LEVELS);

        assertThat(registry.find("notify.delivery.retries").counters()).hasSize(6);
        assertThat(registry.find("notify.delivery.retries").counters())
                .extracting(c -> c.getId().getTag("level")).containsOnly("1", "2");
    }

    @Test
    void durationTimersArePreRegisteredWithTheFourAdapterOutcomes() {
        metrics(THREE_LEVELS);

        List<Timer> timers = List.copyOf(registry.find("notify.delivery.duration").timers());
        assertThat(timers).hasSize(12);
        assertThat(timers).allSatisfy(t -> assertThat(t.count()).isZero());
        assertThat(timers).extracting(t -> t.getId().getTag("outcome"))
                .containsOnly("success", "transient_failure", "permanent_failure", "unknown_failure");
    }

    @Test
    void rejectionsArePreRegisteredForBulkheadAndCircuitBreaker() {
        metrics(THREE_LEVELS);

        List<Counter> rejections = List.copyOf(registry.find("notify.delivery.rejections").counters());
        assertThat(rejections).hasSize(6);
        assertThat(rejections).allSatisfy(c -> assertThat(c.count()).isZero());
        assertThat(rejections).extracting(c -> c.getId().getTag("reason"))
                .containsOnly("bulkhead_full", "circuit_open");
    }

    @Test
    void everyMeterOfTheSameNameHasTheSameTagKeys() {
        metrics(THREE_LEVELS);

        for (String name : List.of("notify.delivery.outcomes", "notify.delivery.retries",
                "notify.delivery.duration", "notify.delivery.rejections")) {
            Set<Set<String>> keySets = registry.find(name).meters().stream()
                    .map(DeliveryMetricsTest::tagKeys)
                    .collect(Collectors.toSet());
            assertThat(keySets).as(name).hasSize(1);
        }
    }

    @Test
    void onlyTheFortyTwoClosedSetSeriesExist() {
        metrics(THREE_LEVELS);

        assertThat(registry.getMeters()).hasSize(15 + 9 + 12 + 6);
        // id, destinatário, template e código de erro nunca viram tag (T-02-24)
        Set<String> allKeys = registry.getMeters().stream()
                .flatMap(m -> tagKeys(m).stream())
                .collect(Collectors.toSet());
        assertThat(allKeys).containsOnly("channel", "outcome", "failure_class", "level", "reason");
    }

    private static Set<String> tagKeys(Meter meter) {
        return meter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet());
    }

    // ------------------------------------------------------------ nomes no Prometheus (D-15)

    @Test
    void sentShowsUpInThePrometheusScrapeWithTheD15Names() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        DeliveryMetrics metrics = new DeliveryMetrics(prometheus, THREE_LEVELS, new FailureClassifier());

        metrics.sent(Channel.EMAIL);

        String scrape = prometheus.scrape();
        List<String> sentLines = scrape.lines()
                .filter(l -> l.startsWith("notify_delivery_outcomes_total{"))
                .filter(l -> l.contains("channel=\"email\""))
                .filter(l -> l.contains("failure_class=\"none\""))
                .filter(l -> l.contains("outcome=\"sent\""))
                .toList();
        assertThat(sentLines).hasSize(1);
        assertThat(sentLines.get(0)).endsWith(" 1.0");
        assertThat(scrape).contains("notify_delivery_retries_total{");
        assertThat(scrape).contains("notify_delivery_duration_seconds_bucket{");
        assertThat(scrape).contains("notify_delivery_rejections_total{");
    }

    // ------------------------------------------------------------ incrementos

    @Test
    void sentIncrementsOnlyTheSentSeriesOfTheChannel() {
        DeliveryMetrics metrics = metrics(THREE_LEVELS);

        metrics.sent(Channel.EMAIL);

        assertThat(outcome("email", "sent", "none")).isEqualTo(1.0);
        assertThat(outcome("sms", "sent", "none")).isZero();
        assertThat(outcome("email", "retrying", "transient")).isZero();
    }

    @Test
    void retryingIncrementsTheRetryingOutcomeAndTheTargetLevel() {
        DeliveryMetrics metrics = metrics(THREE_LEVELS);

        metrics.retrying(Channel.SMS, 2);

        assertThat(outcome("sms", "retrying", "transient")).isEqualTo(1.0);
        assertThat(registry.get("notify.delivery.retries").tags("channel", "sms", "level", "2")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("notify.delivery.retries").tags("channel", "sms", "level", "1")
                .counter().count()).isZero();
    }

    @Test
    void retryingToALevelOutsideTheLadderIsRejected() {
        DeliveryMetrics metrics = metrics(THREE_LEVELS);

        assertThatThrownBy(() -> metrics.retrying(Channel.EMAIL, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.retrying(Channel.EMAIL, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThat(outcome("email", "retrying", "transient")).isZero();
    }

    @Test
    void failedSplitsPermanentAndUnknownAndRefusesTransient() {
        DeliveryMetrics metrics = metrics(THREE_LEVELS);

        metrics.failed(Channel.WEBHOOK, FailureClass.PERMANENT);
        metrics.failed(Channel.WEBHOOK, FailureClass.UNKNOWN);
        metrics.failed(Channel.WEBHOOK, FailureClass.UNKNOWN);

        assertThat(outcome("webhook", "failed", "permanent")).isEqualTo(1.0);
        assertThat(outcome("webhook", "failed", "unknown")).isEqualTo(2.0);
        assertThatThrownBy(() -> metrics.failed(Channel.WEBHOOK, FailureClass.TRANSIENT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deadLetteredAndRejectedIncrementTheirOwnSeries() {
        DeliveryMetrics metrics = metrics(THREE_LEVELS);

        metrics.deadLettered(Channel.EMAIL);
        metrics.rejected(Channel.EMAIL, DeliveryMetrics.RejectionReason.BULKHEAD_FULL);

        assertThat(outcome("email", "dead_lettered", "transient")).isEqualTo(1.0);
        assertThat(registry.get("notify.delivery.rejections").tags("channel", "email", "reason", "bulkhead_full")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("notify.delivery.rejections").tags("channel", "email", "reason", "circuit_open")
                .counter().count()).isZero();
    }

    @Test
    void adapterCallIsTimedWithTheOutcomeOfItsClassificationAndTheFailureIsRethrown() {
        DeliveryMetrics metrics = metrics(THREE_LEVELS);
        SimulatedDeliveryException transientFailure = new SimulatedDeliveryException("falha simulada no canal email");

        metrics.recordAdapterCall(Channel.EMAIL, () -> { });
        assertThatThrownBy(() -> metrics.recordAdapterCall(Channel.EMAIL, () -> {
            throw transientFailure;
        })).isSameAs(transientFailure);
        assertThatThrownBy(() -> metrics.recordAdapterCall(Channel.EMAIL, () -> {
            throw new PermanentDeliveryException(ErrorCode.SIMULATED_PERMANENT, "x");
        })).isInstanceOf(PermanentDeliveryException.class);
        assertThatThrownBy(() -> metrics.recordAdapterCall(Channel.EMAIL, () -> {
            throw new IllegalStateException("bug");
        })).isInstanceOf(IllegalStateException.class);

        for (String outcome : List.of("success", "transient_failure", "permanent_failure", "unknown_failure")) {
            assertThat(registry.get("notify.delivery.duration").tags("channel", "email", "outcome", outcome)
                    .timer().count()).as(outcome).isEqualTo(1);
        }
    }

    private double outcome(String channel, String outcome, String failureClass) {
        return registry.get("notify.delivery.outcomes")
                .tags("channel", channel, "outcome", outcome, "failure_class", failureClass)
                .counter().count();
    }
}
