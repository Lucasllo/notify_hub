package dev.notifyhub.worker.resilience;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.worker.failure.TransientDeliveryException;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;

/**
 * Registries de circuit breaker e bulkhead montados à mão, com a mesma configuração do
 * {@code application.yml} do worker (o {@code ResilienceSettingsTest} confere que o YAML bate com
 * estes valores). Sem Spring.
 */
public final class ResilienceFixtures {

    private ResilienceFixtures() {
    }

    /** CB do YAML: COUNT_BASED 20/10, limiar 50%, 2500ms aberto, 3 em half-open, só transitórias. */
    public static CircuitBreakerConfig yamlCircuitBreakerConfig() {
        return circuitBreakerConfig(Duration.ofMillis(2500));
    }

    public static CircuitBreakerConfig circuitBreakerConfig(Duration waitInOpenState) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(waitInOpenState)
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(TransientDeliveryException.class)
                .ignoreException(new NonTransientFailurePredicate())
                .build();
    }

    /** Bulkhead do YAML: 8 permissões, sem espera. */
    public static BulkheadConfig yamlBulkheadConfig() {
        return bulkheadConfig(8);
    }

    public static BulkheadConfig bulkheadConfig(int maxConcurrentCalls) {
        return BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrentCalls)
                .maxWaitDuration(Duration.ZERO)
                .build();
    }

    /** Registry com uma instância por canal dado (todas com {@code config}). */
    public static CircuitBreakerRegistry circuitBreakers(CircuitBreakerConfig config, Channel... channels) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        for (Channel channel : channels) {
            registry.circuitBreaker(channel.id());
        }
        return registry;
    }

    /** Registry com as instâncias {@code email}, {@code sms} e {@code webhook} do YAML. */
    public static CircuitBreakerRegistry yamlCircuitBreakers() {
        return circuitBreakers(yamlCircuitBreakerConfig(), Channel.values());
    }

    /** Registry com uma instância por canal dado (todas com {@code config}). */
    public static BulkheadRegistry bulkheads(BulkheadConfig config, Channel... channels) {
        BulkheadRegistry registry = BulkheadRegistry.of(config);
        for (Channel channel : channels) {
            registry.bulkhead(channel.id());
        }
        return registry;
    }

    /** Registry com as instâncias {@code email}, {@code sms} e {@code webhook} do YAML. */
    public static BulkheadRegistry yamlBulkheads() {
        return bulkheads(yamlBulkheadConfig(), Channel.values());
    }
}
