package dev.notifyhub.worker.channel;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.RecipientRules;
import dev.notifyhub.common.persistence.RenderedContent;
import dev.notifyhub.worker.channel.MagicRecipients.MagicBehavior;
import dev.notifyhub.worker.channel.SimulationProperties.ChannelSimulation;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter simulado: bloqueia a thread por uma latência aleatória e falha com a probabilidade
 * configurada. Não faz nenhuma chamada de rede — nada sai da máquina, nem com destinatários reais.
 *
 * <p>O {@code Thread.sleep} fica fora de qualquer bloco sincronizado por monitor, que prenderia a
 * virtual thread ao carrier. O bloqueio em si é o ponto da demo de virtual threads.
 */
public class SimulatedChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(SimulatedChannelAdapter.class);

    private final Channel channel;
    private final ChannelSimulation simulation;
    private final Supplier<RandomGenerator> random;

    /**
     * @param random fornece o gerador na thread que envia (ex.: {@code ThreadLocalRandom::current})
     */
    public SimulatedChannelAdapter(Channel channel, ChannelSimulation simulation,
                                   Supplier<RandomGenerator> random) {
        this.channel = channel;
        this.simulation = simulation;
        this.random = random;
    }

    @Override
    public Channel channel() {
        return channel;
    }

    /** Configuração de simulação em uso. */
    public ChannelSimulation simulation() {
        return simulation;
    }

    @Override
    public void send(String recipient, RenderedContent content, AttemptContext context) {
        RandomGenerator rnd = random.get();
        long latencyMs = drawLatencyMs(rnd);
        try {
            Thread.sleep(Duration.ofMillis(latencyMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SimulatedDeliveryException("envio interrompido no canal " + channel.id(), e);
        }
        // depois da latência, como uma falha real do provedor (D-04)
        Optional<MagicBehavior> magic = MagicRecipients.parse(channel, recipient);
        if (magic.isPresent()) {
            applyMagic(magic.get(), context);
        } else if (rnd.nextDouble() < simulation.failureRate()) {
            throw new SimulatedDeliveryException("falha simulada no canal " + channel.id());
        }
        log.info("Envio simulado canal={} destinatario={} latenciaMs={} thread={}",
                channel, RecipientRules.mask(channel, recipient), latencyMs, Thread.currentThread());
    }

    /**
     * Roteiro determinístico do destinatário mágico: quando reconhecido, o sorteio de
     * {@code failure-rate} não se aplica. As mensagens não citam o destinatário.
     */
    private void applyMagic(MagicBehavior magic, AttemptContext context) {
        boolean fails = switch (magic.kind()) {
            case FAIL_N_THEN_OK -> context.retryCount() < magic.failures();
            default -> false;
        };
        if (fails) {
            throw new SimulatedDeliveryException(
                    "falha transitória simulada (destinatário mágico) no canal " + channel.id());
        }
    }

    private long drawLatencyMs(RandomGenerator rnd) {
        long min = simulation.latencyMin().toMillis();
        long max = simulation.latencyMax().toMillis();
        return max > min ? rnd.nextLong(min, max + 1) : min;
    }
}
