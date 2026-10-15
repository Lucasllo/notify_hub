package dev.notifyhub.worker.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.messaging.TopologyProperties;
import dev.notifyhub.common.persistence.RenderedContent;
import dev.notifyhub.worker.channel.AttemptContext;
import dev.notifyhub.worker.channel.ChannelAdapter;
import dev.notifyhub.worker.channel.SimulatedDeliveryException;
import dev.notifyhub.worker.failure.ErrorCode;
import dev.notifyhub.worker.failure.FailureClassifier;
import dev.notifyhub.worker.failure.PermanentDeliveryException;
import dev.notifyhub.worker.metrics.DeliveryMetrics;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Gateway real com registries montados no teste, sem Spring: ordem bulkhead → CB → adapter, o que o
 * CB conta (só transitórias), a recusa imediata do bulkhead e a validação de startup (RES-01, RES-02,
 * D-06, D-07).
 */
class ChannelGatewayTest {

    private static final TopologyProperties TOPOLOGY = new TopologyProperties(List.of(
            Duration.ofSeconds(5), Duration.ofSeconds(25), Duration.ofSeconds(125)));
    private static final RenderedContent CONTENT = new RenderedContent("Olá", "<p>Olá</p>");
    private static final AttemptContext CONTEXT = new AttemptContext(0, 1);

    private final ChannelAdapter email = adapter(Channel.EMAIL);
    private final ChannelAdapter sms = adapter(Channel.SMS);
    private final ChannelAdapter webhook = adapter(Channel.WEBHOOK);
    private final CircuitBreakerRegistry circuitBreakers = ResilienceFixtures.yamlCircuitBreakers();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DeliveryMetrics metrics = new DeliveryMetrics(meters, TOPOLOGY, new FailureClassifier());

    private static ChannelAdapter adapter(Channel channel) {
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        when(adapter.channel()).thenReturn(channel);
        return adapter;
    }

    private ChannelGateway gateway() {
        return new ChannelGateway(List.of(email, sms, webhook), circuitBreakers,
                ResilienceFixtures.yamlBulkheads(), TOPOLOGY, metrics);
    }

    private CircuitBreaker emailBreaker() {
        return circuitBreakers.find("email").orElseThrow();
    }

    private void sendEmailTimesIgnoringFailures(ChannelGateway gateway, int times) {
        for (int i = 0; i < times; i++) {
            try {
                gateway.send(Channel.EMAIL, "ana@exemplo.com", CONTENT, CONTEXT);
            } catch (RuntimeException expected) {
                // a falha do adapter sobe sem alteração
            }
        }
    }

    private void emailAdapterThrows(Supplier<RuntimeException> failure) {
        doThrow(failure.get()).when(email).send(anyString(), any(), any());
    }

    // ------------------------------------------------------------ o que o CB conta (D-06)

    @Test
    void tenPermanentFailuresDoNotOpenTheCircuitBreaker() {
        emailAdapterThrows(() -> new PermanentDeliveryException(ErrorCode.SIMULATED_PERMANENT, "x"));
        ChannelGateway gateway = gateway();

        sendEmailTimesIgnoringFailures(gateway, 10);

        assertThat(emailBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(emailBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
        verify(email, times(10)).send(anyString(), any(), any());
    }

    @Test
    void tenTransientFailuresOpenTheCircuitBreakerAndTheEleventhCallIsRefusedWithoutCallingTheAdapter() {
        emailAdapterThrows(() -> new SimulatedDeliveryException("falha simulada no canal email"));
        ChannelGateway gateway = gateway();

        sendEmailTimesIgnoringFailures(gateway, 10);

        assertThat(emailBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> gateway.send(Channel.EMAIL, "ana@exemplo.com", CONTENT, CONTEXT))
                .isInstanceOf(CallNotPermittedException.class);
        verify(email, times(10)).send(anyString(), any(), any());
    }

    @Test
    void tenUnknownFailuresDoNotOpenTheCircuitBreaker() {
        emailAdapterThrows(() -> new IllegalStateException("bug"));
        ChannelGateway gateway = gateway();

        sendEmailTimesIgnoringFailures(gateway, 10);

        assertThat(emailBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void adapterFailurePropagatesUnchangedThroughBothDecorators() {
        SimulatedDeliveryException failure = new SimulatedDeliveryException("falha simulada no canal email");
        doThrow(failure).when(email).send(anyString(), any(), any());

        assertThatThrownBy(() -> gateway().send(Channel.EMAIL, "ana@exemplo.com", CONTENT, CONTEXT))
                .isSameAs(failure);
    }

    @Test
    void openCircuitBreakerOfOneChannelDoesNotAffectTheOthers() {
        ChannelGateway gateway = gateway();
        emailBreaker().transitionToOpenState();

        assertThatCode(() -> gateway.send(Channel.SMS, "+5511999990000", CONTENT, CONTEXT))
                .doesNotThrowAnyException();
        verify(sms).send("+5511999990000", CONTENT, CONTEXT);
        verify(email, never()).send(anyString(), any(), any());
    }

    // ------------------------------------------------------------ bulkhead por semáforo (D-07)

    @Test
    void fullBulkheadRefusesTheSecondCallImmediatelyAndReleasesThePermitAfterwards() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ChannelAdapter blocking = new ChannelAdapter() {
            @Override
            public Channel channel() {
                return Channel.EMAIL;
            }

            @Override
            public void send(String recipient, RenderedContent content, AttemptContext context) {
                calls.incrementAndGet();
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        BulkheadRegistry onePermit = ResilienceFixtures.bulkheads(
                ResilienceFixtures.bulkheadConfig(1), Channel.values());
        ChannelGateway gateway = new ChannelGateway(List.of(blocking, sms, webhook), circuitBreakers,
                onePermit, TOPOLOGY, metrics);

        Thread first = Thread.ofVirtual().start(
                () -> gateway.send(Channel.EMAIL, "ana@exemplo.com", CONTENT, CONTEXT));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        assertThatThrownBy(() -> gateway.send(Channel.EMAIL, "bia@exemplo.com", CONTENT, CONTEXT))
                .isInstanceOf(BulkheadFullException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(500L);
        assertThat(calls).hasValue(1);

        release.countDown();
        first.join(5_000);
        assertThat(first.isAlive()).isFalse();

        assertThatCode(() -> gateway.send(Channel.EMAIL, "caio@exemplo.com", CONTENT, CONTEXT))
                .doesNotThrowAnyException();
        assertThat(calls).hasValue(2);
        // recusa do bulkhead fica fora da janela do CB
        assertThat(emailBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    // ------------------------------------------------------------ métricas do adapter e recusas (D-15)

    private double durationCount(String outcome) {
        return meters.get("notify.delivery.duration").tags("channel", "email", "outcome", outcome)
                .timer().count();
    }

    private long allDurationCounts() {
        return meters.find("notify.delivery.duration").timers().stream().mapToLong(t -> t.count()).sum();
    }

    private double rejections(String reason) {
        return meters.get("notify.delivery.rejections").tags("channel", "email", "reason", reason)
                .counter().count();
    }

    @Test
    void successfulAdapterCallIsTimedAsSuccess() {
        gateway().send(Channel.EMAIL, "ana@exemplo.com", CONTENT, CONTEXT);

        assertThat(durationCount("success")).isEqualTo(1);
        assertThat(allDurationCounts()).isEqualTo(1);
    }

    @Test
    void transientAdapterFailureIsTimedAsTransientAndStillReachesTheCaller() {
        SimulatedDeliveryException failure = new SimulatedDeliveryException("falha simulada no canal email");
        doThrow(failure).when(email).send(anyString(), any(), any());

        assertThatThrownBy(() -> gateway().send(Channel.EMAIL, "ana@exemplo.com", CONTENT, CONTEXT))
                .isSameAs(failure);

        assertThat(durationCount("transient_failure")).isEqualTo(1);
        assertThat(allDurationCounts()).isEqualTo(1);
    }

    @Test
    void permanentAdapterFailureIsTimedAsPermanent() {
        emailAdapterThrows(() -> new PermanentDeliveryException(ErrorCode.SIMULATED_PERMANENT, "x"));

        assertThatThrownBy(() -> gateway().send(Channel.EMAIL, "ana@exemplo.com", CONTENT, CONTEXT))
                .isInstanceOf(PermanentDeliveryException.class);

        assertThat(durationCount("permanent_failure")).isEqualTo(1);
    }

    @Test
    void openCircuitBreakerRefusalIsCountedAsRejectionAndNeverTimed() {
        ChannelGateway gateway = gateway();
        emailBreaker().transitionToOpenState();

        assertThatThrownBy(() -> gateway.send(Channel.EMAIL, "ana@exemplo.com", CONTENT, CONTEXT))
                .isInstanceOf(CallNotPermittedException.class);

        assertThat(rejections("circuit_open")).isEqualTo(1);
        assertThat(rejections("bulkhead_full")).isZero();
        assertThat(allDurationCounts()).isZero();
    }

    @Test
    void fullBulkheadRefusalIsCountedAsRejectionAndNeverTimed() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChannelAdapter blocking = new ChannelAdapter() {
            @Override
            public Channel channel() {
                return Channel.EMAIL;
            }

            @Override
            public void send(String recipient, RenderedContent content, AttemptContext context) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        ChannelGateway gateway = new ChannelGateway(List.of(blocking, sms, webhook), circuitBreakers,
                ResilienceFixtures.bulkheads(ResilienceFixtures.bulkheadConfig(1), Channel.values()), TOPOLOGY,
                metrics);
        Thread first = Thread.ofVirtual().start(
                () -> gateway.send(Channel.EMAIL, "ana@exemplo.com", CONTENT, CONTEXT));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> gateway.send(Channel.EMAIL, "bia@exemplo.com", CONTENT, CONTEXT))
                .isInstanceOf(BulkheadFullException.class);

        assertThat(rejections("bulkhead_full")).isEqualTo(1);
        assertThat(rejections("circuit_open")).isZero();
        // a chamada presa ainda não terminou; a recusa não entrou no timer
        assertThat(allDurationCounts()).isZero();

        release.countDown();
        first.join(5_000);
        assertThat(durationCount("success")).isEqualTo(1);
        assertThat(allDurationCounts()).isEqualTo(1);
    }

    // ------------------------------------------------------------ validação de startup

    @Test
    void missingCircuitBreakerInstanceRefusesToStart() {
        CircuitBreakerRegistry withoutSms = ResilienceFixtures.circuitBreakers(
                ResilienceFixtures.yamlCircuitBreakerConfig(), Channel.EMAIL, Channel.WEBHOOK);

        assertThatThrownBy(() -> new ChannelGateway(List.of(email, sms, webhook), withoutSms,
                ResilienceFixtures.yamlBulkheads(), TOPOLOGY, metrics))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("circuit breaker")
                .hasMessageContaining("sms");
    }

    @Test
    void missingBulkheadInstanceRefusesToStart() {
        BulkheadRegistry withoutWebhook = ResilienceFixtures.bulkheads(
                ResilienceFixtures.yamlBulkheadConfig(), Channel.EMAIL, Channel.SMS);

        assertThatThrownBy(() -> new ChannelGateway(List.of(email, sms, webhook), circuitBreakers,
                withoutWebhook, TOPOLOGY, metrics))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bulkhead")
                .hasMessageContaining("webhook");
    }

    @Test
    void missingAdapterRefusesToStart() {
        assertThatThrownBy(() -> new ChannelGateway(List.of(email, sms), circuitBreakers,
                ResilienceFixtures.yamlBulkheads(), TOPOLOGY, metrics))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEBHOOK");
    }

    @Test
    void duplicateAdapterRefusesToStart() {
        assertThatThrownBy(() -> new ChannelGateway(List.of(email, sms, webhook, email), circuitBreakers,
                ResilienceFixtures.yamlBulkheads(), TOPOLOGY, metrics))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void openWaitLongerThanTheFirstRetryLevelRefusesToStart() {
        CircuitBreakerRegistry sixSeconds = ResilienceFixtures.circuitBreakers(
                ResilienceFixtures.circuitBreakerConfig(Duration.ofSeconds(6)), Channel.values());

        assertThatThrownBy(() -> new ChannelGateway(List.of(email, sms, webhook), sixSeconds,
                ResilienceFixtures.yamlBulkheads(), TOPOLOGY, metrics))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wait-duration-in-open-state")
                .hasMessageContaining("email");
    }

    @Test
    void openWaitIsCheckedForEveryChannelInstance() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(ResilienceFixtures.yamlCircuitBreakerConfig());
        registry.circuitBreaker("email");
        registry.circuitBreaker("sms", ResilienceFixtures.circuitBreakerConfig(Duration.ofSeconds(6)));
        registry.circuitBreaker("webhook");

        assertThatThrownBy(() -> new ChannelGateway(List.of(email, sms, webhook), registry,
                ResilienceFixtures.yamlBulkheads(), TOPOLOGY, metrics))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sms");
    }

    @Test
    void openWaitEqualToTheFirstRetryLevelStartsNormally() {
        CircuitBreakerRegistry fiveSeconds = ResilienceFixtures.circuitBreakers(
                ResilienceFixtures.circuitBreakerConfig(Duration.ofSeconds(5)), Channel.values());

        assertThatCode(() -> new ChannelGateway(List.of(email, sms, webhook), fiveSeconds,
                ResilienceFixtures.yamlBulkheads(), TOPOLOGY, metrics))
                .doesNotThrowAnyException();
    }
}
