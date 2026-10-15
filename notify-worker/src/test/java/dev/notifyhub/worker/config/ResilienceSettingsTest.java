package dev.notifyhub.worker.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.notifyhub.common.messaging.TopologyProperties;
import dev.notifyhub.worker.channel.SimulatedDeliveryException;
import dev.notifyhub.worker.failure.ErrorCode;
import dev.notifyhub.worker.failure.PermanentDeliveryException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Configuração real do Resilience4j no worker: o {@code application.yml} (e o profile {@code demo})
 * carregado pela autoconfiguração do {@code resilience4j-spring-boot3}, lendo as configs que os
 * registries de fato montam, sem Docker (D-06, D-07, D-08, T-02-21, T-02-23).
 */
class ResilienceSettingsTest {

    private static final List<String> CHANNELS = List.of("email", "sms", "webhook");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    CircuitBreakerAutoConfiguration.class, BulkheadAutoConfiguration.class));

    private static long waitInOpenStateMs(CircuitBreakerConfig config) {
        return config.getWaitIntervalFunctionInOpenState().apply(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "sms", "webhook"})
    void everyChannelHasTheSensitiveFastRecoveringCircuitBreakerOfTheYaml(String channel) {
        runner.run(ctx -> {
            CircuitBreakerConfig cfg = ctx.getBean(CircuitBreakerRegistry.class)
                    .find(channel).orElseThrow().getCircuitBreakerConfig();

            assertThat(cfg.getSlidingWindowType()).isEqualTo(SlidingWindowType.COUNT_BASED);
            assertThat(cfg.getSlidingWindowSize()).isEqualTo(20);
            assertThat(cfg.getMinimumNumberOfCalls()).isEqualTo(10);
            assertThat(cfg.getFailureRateThreshold()).isEqualTo(50f);
            assertThat(waitInOpenStateMs(cfg)).isEqualTo(2500L);
            assertThat(cfg.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
            assertThat(cfg.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "sms", "webhook"})
    void everyChannelCircuitBreakerRecordsOnlyTransientFailures(String channel) {
        runner.run(ctx -> {
            CircuitBreakerConfig cfg = ctx.getBean(CircuitBreakerRegistry.class)
                    .find(channel).orElseThrow().getCircuitBreakerConfig();
            SimulatedDeliveryException transientFailure = new SimulatedDeliveryException("x");
            PermanentDeliveryException permanent = new PermanentDeliveryException(ErrorCode.SIMULATED_PERMANENT, "x");

            assertThat(cfg.getRecordExceptionPredicate().test(transientFailure)).isTrue();
            assertThat(cfg.getIgnoreExceptionPredicate().test(transientFailure)).isFalse();
            assertThat(cfg.getIgnoreExceptionPredicate().test(permanent)).isTrue();
            assertThat(cfg.getIgnoreExceptionPredicate().test(new IllegalStateException("bug"))).isTrue();
            assertThat(cfg.getIgnoreExceptionPredicate().test(
                    BulkheadFullException.createBulkheadFullException(Bulkhead.ofDefaults(channel)))).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "sms", "webhook"})
    void everyChannelBulkheadRefusesImmediatelyBelowTheListenerConcurrency(String channel) {
        runner.run(ctx -> {
            BulkheadConfig cfg = ctx.getBean(BulkheadRegistry.class).find(channel).orElseThrow().getBulkheadConfig();
            Integer concurrency = ctx.getEnvironment()
                    .getProperty("notify.worker.channels." + channel + ".concurrency", Integer.class);

            assertThat(cfg.getMaxConcurrentCalls()).isEqualTo(8);
            assertThat(concurrency).isEqualTo(10);
            assertThat(cfg.getMaxConcurrentCalls()).isLessThan(concurrency);
            assertThat(cfg.getMaxWaitDuration()).isZero();
        });
    }

    @Test
    void defaultOpenWaitDoesNotExceedTheDefaultFirstRetryLevel() {
        runner.run(ctx -> {
            Duration firstLevel = firstRetryLevel(ctx.getEnvironment());
            assertThat(firstLevel).isEqualTo(Duration.ofSeconds(5));
            for (String channel : CHANNELS) {
                CircuitBreakerConfig cfg = ctx.getBean(CircuitBreakerRegistry.class)
                        .find(channel).orElseThrow().getCircuitBreakerConfig();
                assertThat(waitInOpenStateMs(cfg)).isLessThanOrEqualTo(firstLevel.toMillis());
            }
        });
    }

    @Test
    void demoProfileOpensForHalfOfItsOneSecondFirstRetryLevel() {
        runner.withPropertyValues("spring.profiles.active=demo").run(ctx -> {
            Duration firstLevel = firstRetryLevel(ctx.getEnvironment());
            assertThat(firstLevel).isEqualTo(Duration.ofSeconds(1));
            for (String channel : CHANNELS) {
                CircuitBreakerConfig cfg = ctx.getBean(CircuitBreakerRegistry.class)
                        .find(channel).orElseThrow().getCircuitBreakerConfig();
                assertThat(waitInOpenStateMs(cfg)).as(channel).isEqualTo(500L);
                assertThat(waitInOpenStateMs(cfg)).isLessThanOrEqualTo(firstLevel.toMillis());
            }
        });
    }

    /** 1º nível como o worker o enxerga: bind do próprio {@link TopologyProperties}, com os padrões dele. */
    private static Duration firstRetryLevel(Environment environment) {
        return Binder.get(environment).bindOrCreate("notify.topology", TopologyProperties.class)
                .retryLevels().get(0);
    }

    // ------------------------------------------------------------ chaves cruas do YAML (D-08)

    private static PropertySource<?> yaml(String name) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(name, new ClassPathResource(name));
        assertThat(sources).as(name).hasSize(1);
        return sources.get(0);
    }

    @Test
    void thresholdsOpenWaitAndPermitsAreOverridableByEnvironment() throws IOException {
        PropertySource<?> base = yaml("application.yml");
        String cb = "resilience4j.circuitbreaker.configs.default.";

        assertThat(base.getProperty(cb + "sliding-window-size")).isEqualTo("${NOTIFY_CB_WINDOW_SIZE:20}");
        assertThat(base.getProperty(cb + "minimum-number-of-calls")).isEqualTo("${NOTIFY_CB_MIN_CALLS:10}");
        assertThat(base.getProperty(cb + "failure-rate-threshold")).isEqualTo("${NOTIFY_CB_FAILURE_RATE:50}");
        assertThat(base.getProperty(cb + "wait-duration-in-open-state")).isEqualTo("${NOTIFY_CB_OPEN_WAIT:2500ms}");
        assertThat(base.getProperty("resilience4j.bulkhead.configs.default.max-concurrent-calls"))
                .isEqualTo("${NOTIFY_BULKHEAD_MAX_CONCURRENT:8}");
        for (String channel : CHANNELS) {
            assertThat(base.getProperty("resilience4j.circuitbreaker.instances." + channel + ".base-config"))
                    .isEqualTo("default");
            assertThat(base.getProperty("resilience4j.bulkhead.instances." + channel + ".base-config"))
                    .isEqualTo("default");
        }
    }

    @Test
    void noResilienceHealthIndicatorIsTurnedOn() throws IOException {
        for (String file : List.of("application.yml", "application-demo.yml")) {
            EnumerablePropertySource<?> source = (EnumerablePropertySource<?>) yaml(file);
            List<String> healthKeys = Stream.of(source.getPropertyNames())
                    .filter(key -> key.contains("register-health-indicator")
                            || key.startsWith("management.health.circuitbreakers")
                            || key.startsWith("management.health.bulkheads"))
                    .filter(key -> "true".equals(String.valueOf(source.getProperty(key))))
                    .toList();
            assertThat(healthKeys).as(file).isEmpty();
        }
    }
}
