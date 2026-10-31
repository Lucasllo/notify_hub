package dev.notifyhub.worker.metrics;

import dev.notifyhub.common.domain.AttemptOutcome;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.FailureClass;
import dev.notifyhub.common.messaging.TopologyProperties;
import dev.notifyhub.worker.failure.FailureClassifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Métricas de entrega do D-15 (OBS-01), contrato do dashboard provisionado da Fase 4.
 *
 * <p>Todas as séries são <b>pré-registradas no construtor</b>, com valor 0, para o Prometheus e o
 * Grafana verem a série antes da primeira mensagem. As tags têm conjunto fechado, valores em
 * minúsculo vindos de {@link Channel#id()} e de enums, e o mesmo conjunto de chaves por nome (o
 * registry Prometheus descarta meters de mesmo nome com chaves diferentes; por isso {@code sent} leva
 * {@code failure_class="none"}). Id da notificação, destinatário, template e código de erro detalhado
 * nunca viram tag: ficam no Mongo e no log (T-02-24).
 *
 * <ul>
 *   <li>{@value #OUTCOMES}{@code {channel, outcome, failure_class}}: sent/none, retrying/transient,
 *       dead_lettered/transient, failed/permanent, failed/unknown → 15 séries;</li>
 *   <li>{@value #RETRIES}{@code {channel, level}}: level de 1 ao número de níveis de
 *       {@code notify.topology.retry-levels} → 9 séries com 3 níveis;</li>
 *   <li>{@value #DURATION}{@code {channel, outcome}}: só a chamada ao adapter, com histograma para o
 *       p95; outcome success|transient_failure|permanent_failure|unknown_failure → 12 séries;</li>
 *   <li>{@value #REJECTIONS}{@code {channel, reason}}: bulkhead_full|circuit_open → 6 séries (o
 *       Resilience4j não publica contador de recusas do bulkhead).</li>
 * </ul>
 *
 * <p>Quem chama incrementa só quando o resultado é real: depois de o update condicional ganhar o fence
 * e, nos ramos com publish, depois do confirm. Fence perdido não conta nada.
 */
@Component
public class DeliveryMetrics {

    public static final String OUTCOMES = "notify.delivery.outcomes";
    public static final String RETRIES = "notify.delivery.retries";
    public static final String DURATION = "notify.delivery.duration";
    public static final String REJECTIONS = "notify.delivery.rejections";

    private static final Duration MIN_EXPECTED = Duration.ofMillis(1);
    private static final Duration MAX_EXPECTED = Duration.ofSeconds(30);

    /** Resultados do adapter que têm timer de duração (D-15): 4 por canal, 12 séries. */
    private static final Set<AttemptOutcome> ADAPTER_OUTCOMES = Collections.unmodifiableSet(EnumSet.of(
            AttemptOutcome.SUCCESS, AttemptOutcome.TRANSIENT_FAILURE,
            AttemptOutcome.PERMANENT_FAILURE, AttemptOutcome.UNKNOWN_FAILURE));

    private final MeterRegistry registry;
    private final FailureClassifier classifier;
    private final Map<Channel, ChannelMeters> meters = new EnumMap<>(Channel.class);

    /**
     * @param topology   níveis da escada; definem as séries de {@value #RETRIES}
     * @param classifier deriva o {@code outcome} do timer da exceção do adapter
     */
    public DeliveryMetrics(MeterRegistry registry, TopologyProperties topology, FailureClassifier classifier) {
        this.registry = registry;
        this.classifier = classifier;
        int levels = topology.retryLevels().size();
        for (Channel channel : Channel.values()) {
            meters.put(channel, register(registry, channel, levels));
        }
    }

    private static ChannelMeters register(MeterRegistry registry, Channel channel, int levels) {
        List<Counter> retries = new ArrayList<>(levels);
        for (int level = 1; level <= levels; level++) {
            retries.add(Counter.builder(RETRIES)
                    .description("Retries publicados (com confirm) na fila de delay, por nível")
                    .tag("channel", channel.id())
                    .tag("level", Integer.toString(level))
                    .register(registry));
        }
        Map<AttemptOutcome, Timer> durations = new EnumMap<>(AttemptOutcome.class);
        // Conjunto explícito dos resultados do adapter, e não o enum inteiro: REPROCESSED (Fase 3) é
        // evento do operador, nunca resultado do adapter, e criaria séries que jamais recebem amostra
        for (AttemptOutcome outcome : ADAPTER_OUTCOMES) {
            durations.put(outcome, Timer.builder(DURATION)
                    .description("Duração da chamada ao adapter do canal, sem recusas de CB/bulkhead")
                    .tag("channel", channel.id())
                    .tag("outcome", tagValue(outcome))
                    .publishPercentileHistogram()
                    .minimumExpectedValue(MIN_EXPECTED)
                    .maximumExpectedValue(MAX_EXPECTED)
                    .register(registry));
        }
        Map<RejectionReason, Counter> rejections = new EnumMap<>(RejectionReason.class);
        for (RejectionReason reason : RejectionReason.values()) {
            rejections.put(reason, Counter.builder(REJECTIONS)
                    .description("Chamadas recusadas pelo bulkhead ou pelo circuit breaker do canal")
                    .tag("channel", channel.id())
                    .tag("reason", tagValue(reason))
                    .register(registry));
        }
        return new ChannelMeters(
                outcome(registry, channel, "sent", "none"),
                outcome(registry, channel, "retrying", "transient"),
                outcome(registry, channel, "dead_lettered", "transient"),
                outcome(registry, channel, "failed", "permanent"),
                outcome(registry, channel, "failed", "unknown"),
                List.copyOf(retries), durations, rejections);
    }

    private static Counter outcome(MeterRegistry registry, Channel channel, String outcome, String failureClass) {
        return Counter.builder(OUTCOMES)
                .description("Resultados de entrega gravados no documento, por canal")
                .tag("channel", channel.id())
                .tag("outcome", outcome)
                .tag("failure_class", failureClass)
                .register(registry);
    }

    private static String tagValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** SENT gravado (fence ganho). */
    public void sent(Channel channel) {
        meters.get(channel).sent().increment();
    }

    /**
     * RETRYING gravado e token confirmado na fila de delay do nível {@code level}.
     *
     * @param level nível de destino, de 1 ao número de níveis ({@code h+1})
     * @throws IllegalArgumentException se o nível não existir na escada
     */
    public void retrying(Channel channel, int level) {
        ChannelMeters m = meters.get(channel);
        if (level < 1 || level > m.retries().size()) {
            throw new IllegalArgumentException("nível de retry inexistente: " + level
                    + " (escada com " + m.retries().size() + " níveis)");
        }
        m.retrying().increment();
        m.retries().get(level - 1).increment();
    }

    /**
     * FAILED gravado (fence ganho).
     *
     * @throws IllegalArgumentException se a classe for TRANSIENT, que nunca vira FAILED
     */
    public void failed(Channel channel, FailureClass failureClass) {
        ChannelMeters m = meters.get(channel);
        switch (failureClass) {
            case PERMANENT -> m.failedPermanent().increment();
            case UNKNOWN -> m.failedUnknown().increment();
            case TRANSIENT -> throw new IllegalArgumentException("falha transitória nunca vira FAILED");
        }
    }

    /** DEAD_LETTERED gravado (escada esgotada ou republicação falha). */
    public void deadLettered(Channel channel) {
        meters.get(channel).deadLettered().increment();
    }

    /** Chamada recusada pelo bulkhead ou pelo circuit breaker; não entra no timer. */
    public void rejected(Channel channel, RejectionReason reason) {
        meters.get(channel).rejections().get(reason).increment();
    }

    /**
     * Executa e mede a chamada ao adapter. Sucesso registra {@code outcome=success}; uma
     * {@link RuntimeException} registra o outcome da classificação e é relançada sem alteração.
     * {@link Error} não é medido nem capturado.
     */
    public void recordAdapterCall(Channel channel, Runnable call) {
        Map<AttemptOutcome, Timer> durations = meters.get(channel).durations();
        Timer.Sample sample = Timer.start(registry);
        try {
            call.run();
        } catch (RuntimeException e) {
            sample.stop(durations.get(classifier.classify(e).outcome()));
            throw e;
        }
        sample.stop(durations.get(AttemptOutcome.SUCCESS));
    }

    /** Motivo da recusa antes do adapter (tag {@code reason}). */
    public enum RejectionReason {
        BULKHEAD_FULL,
        CIRCUIT_OPEN
    }

    private record ChannelMeters(Counter sent, Counter retrying, Counter deadLettered, Counter failedPermanent,
                                 Counter failedUnknown, List<Counter> retries,
                                 Map<AttemptOutcome, Timer> durations, Map<RejectionReason, Counter> rejections) {
    }
}
