package dev.notifyhub.worker.channel;

import dev.notifyhub.common.domain.Channel;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Simulação por canal em {@code notify.simulation.<email|sms|webhook>.*}: {@code latency-min},
 * {@code latency-max} e {@code failure-rate}. Os padrões da Fase 1 são 50ms, 200ms e 0.0, porque o
 * caminho de retry só chega na Fase 2.
 */
@ConfigurationProperties("notify.simulation")
public record SimulationProperties(
        @DefaultValue ChannelSimulation email,
        @DefaultValue ChannelSimulation sms,
        @DefaultValue ChannelSimulation webhook,
        @DefaultValue("true") boolean magicRecipients) {

    /** Configuração de simulação do canal informado. */
    public ChannelSimulation forChannel(Channel channel) {
        return switch (channel) {
            case EMAIL -> email;
            case SMS -> sms;
            case WEBHOOK -> webhook;
        };
    }

    /**
     * Latência bloqueante uniforme em [latencyMin, latencyMax] e probabilidade de falha.
     *
     * @param latencyMin  latência mínima (≥ 0)
     * @param latencyMax  latência máxima (≥ latencyMin)
     * @param failureRate probabilidade de falha em [0, 1]
     */
    public record ChannelSimulation(
            @DefaultValue("50ms") Duration latencyMin,
            @DefaultValue("200ms") Duration latencyMax,
            @DefaultValue("0.0") double failureRate) {

        public ChannelSimulation {
            if (latencyMin == null || latencyMax == null) {
                throw new IllegalArgumentException("latency-min e latency-max são obrigatórios");
            }
            if (latencyMin.isNegative()) {
                throw new IllegalArgumentException("latency-min não pode ser negativo: " + latencyMin);
            }
            if (latencyMin.compareTo(latencyMax) > 0) {
                throw new IllegalArgumentException(
                        "latency-min (" + latencyMin + ") maior que latency-max (" + latencyMax + ")");
            }
            if (!(failureRate >= 0.0 && failureRate <= 1.0)) {
                throw new IllegalArgumentException("failure-rate deve estar em [0, 1]: " + failureRate);
            }
        }
    }
}
