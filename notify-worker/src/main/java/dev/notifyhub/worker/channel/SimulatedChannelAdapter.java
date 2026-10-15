package dev.notifyhub.worker.channel;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.RecipientRules;
import dev.notifyhub.common.persistence.RenderedContent;
import dev.notifyhub.worker.channel.MagicRecipients.MagicBehavior;
import dev.notifyhub.worker.channel.SimulationProperties.ChannelSimulation;
import dev.notifyhub.worker.failure.ErrorCode;
import dev.notifyhub.worker.failure.PermanentDeliveryException;
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
 * <p>Depois da latência, um destinatário mágico ({@link MagicRecipients}) executa o seu roteiro
 * determinístico com as mesmas exceções de uma falha real do provedor:
 * {@link SimulatedDeliveryException} (transitória) e {@link PermanentDeliveryException} com
 * {@link ErrorCode#SIMULATED_PERMANENT}. Assim a falha mágica passa pelo bulkhead e pelo circuit
 * breaker que envolvem o adapter (D-04).
 *
 * <p>O {@code Thread.sleep} fica fora de qualquer bloco sincronizado por monitor, que prenderia a
 * virtual thread ao carrier. O bloqueio em si é o ponto da demo de virtual threads.
 */
public class SimulatedChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(SimulatedChannelAdapter.class);

    private final Channel channel;
    private final ChannelSimulation simulation;
    private final Supplier<RandomGenerator> random;
    private final boolean magicRecipients;

    /**
     * Adapter com destinatários mágicos ligados.
     *
     * @param random fornece o gerador na thread que envia (ex.: {@code ThreadLocalRandom::current})
     */
    public SimulatedChannelAdapter(Channel channel, ChannelSimulation simulation,
                                   Supplier<RandomGenerator> random) {
        this(channel, simulation, random, true);
    }

    /**
     * @param random          fornece o gerador na thread que envia (ex.: {@code ThreadLocalRandom::current})
     * @param magicRecipients {@code false} desliga o reconhecimento de destinatários mágicos: todo
     *                        destinatário segue latência + {@code failure-rate}
     */
    public SimulatedChannelAdapter(Channel channel, ChannelSimulation simulation,
                                   Supplier<RandomGenerator> random, boolean magicRecipients) {
        this.channel = channel;
        this.simulation = simulation;
        this.random = random;
        this.magicRecipients = magicRecipients;
    }

    @Override
    public Channel channel() {
        return channel;
    }

    /** Configuração de simulação em uso. */
    public ChannelSimulation simulation() {
        return simulation;
    }

    /** Diz se o reconhecimento de destinatários mágicos está ligado. */
    public boolean magicRecipients() {
        return magicRecipients;
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
        Optional<MagicBehavior> magic = magicRecipients
                ? MagicRecipients.parse(channel, recipient)
                : Optional.empty();
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
        switch (magic.kind()) {
            case ALWAYS_TRANSIENT -> throw magicTransient();
            case ALWAYS_PERMANENT -> throw new PermanentDeliveryException(ErrorCode.SIMULATED_PERMANENT,
                    "destinatário rejeitado pelo provedor simulado (destinatário mágico) no canal " + channel.id());
            case FAIL_N_THEN_OK -> {
                // D-02: conta o nível de retry, que o reprocessamento zera
                if (context.retryCount() < magic.failures()) {
                    throw magicTransient();
                }
            }
            case FAIL_UNTIL_REPROCESS -> {
                // D-03: conta a tentativa total, que o reprocessamento não zera
                if (context.attemptNumber() <= MagicRecipients.FAIL_UNTIL_REPROCESS_ATTEMPTS) {
                    throw magicTransient();
                }
            }
        }
    }

    private SimulatedDeliveryException magicTransient() {
        return new SimulatedDeliveryException(
                "falha transitória simulada (destinatário mágico) no canal " + channel.id());
    }

    private long drawLatencyMs(RandomGenerator rnd) {
        long min = simulation.latencyMin().toMillis();
        long max = simulation.latencyMax().toMillis();
        return max > min ? rnd.nextLong(min, max + 1) : min;
    }
}
