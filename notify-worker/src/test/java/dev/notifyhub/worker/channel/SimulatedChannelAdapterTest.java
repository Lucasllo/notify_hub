package dev.notifyhub.worker.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.persistence.RenderedContent;
import dev.notifyhub.worker.channel.SimulationProperties.ChannelSimulation;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SimulatedChannelAdapterTest {

    private static final RenderedContent CONTENT = new RenderedContent(null, "Olá, Ana!");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SimulationConfiguration.class);

    @Test
    void alwaysFailsWhenFailureRateIsOne() {
        SimulatedChannelAdapter adapter = adapter(Channel.SMS, simulation(0, 0, 1.0));

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> adapter.send("+5511999990000", CONTENT))
                    .isInstanceOf(SimulatedDeliveryException.class)
                    .hasMessageContaining("sms");
        }
    }

    @Test
    void neverFailsWhenFailureRateIsZero() {
        SimulatedChannelAdapter adapter = adapter(Channel.EMAIL, simulation(0, 0, 0.0));

        for (int i = 0; i < 20; i++) {
            assertThatCode(() -> adapter.send("ana@exemplo.com", CONTENT)).doesNotThrowAnyException();
        }
    }

    @Test
    void blocksForAtLeastTheMinimumLatency() {
        SimulatedChannelAdapter adapter = adapter(Channel.WEBHOOK, simulation(30, 30, 0.0));

        long start = System.nanoTime();
        adapter.send("https://hooks.exemplo.com/notify", CONTENT);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }

    @Test
    void zeroLatencyReturnsQuickly() {
        SimulatedChannelAdapter adapter = adapter(Channel.EMAIL, simulation(0, 0, 0.0));

        long start = System.nanoTime();
        adapter.send("ana@exemplo.com", CONTENT);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(elapsedMs).isLessThan(200);
    }

    @Test
    void interruptedSendRestoresFlagAndFails() {
        SimulatedChannelAdapter adapter = adapter(Channel.EMAIL, simulation(5_000, 5_000, 0.0));

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> adapter.send("ana@exemplo.com", CONTENT))
                    .isInstanceOf(SimulatedDeliveryException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsMinimumAboveMaximum() {
        assertThatThrownBy(() -> simulation(200, 50, 0.0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeLatency() {
        assertThatThrownBy(() -> simulation(-1, 50, 0.0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFailureRateOutsideUnitInterval() {
        assertThatThrownBy(() -> simulation(0, 0, -0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> simulation(0, 0, 1.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> simulation(0, 0, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registersOneAdapterPerChannelWithPhaseOneDefaults() {
        contextRunner.run(context -> {
            Map<Channel, SimulatedChannelAdapter> adapters = adaptersByChannel(context);

            assertThat(adapters).containsOnlyKeys(Channel.EMAIL, Channel.SMS, Channel.WEBHOOK);
            ChannelSimulation defaults = simulation(50, 200, 0.0);
            adapters.values().forEach(adapter -> assertThat(adapter.simulation()).isEqualTo(defaults));
        });
    }

    @Test
    void channelPropertyOverridesOnlyThatChannel() {
        contextRunner.withPropertyValues("notify.simulation.sms.failure-rate=0.5").run(context -> {
            Map<Channel, SimulatedChannelAdapter> adapters = adaptersByChannel(context);

            assertThat(adapters.get(Channel.SMS).simulation().failureRate()).isEqualTo(0.5);
            assertThat(adapters.get(Channel.SMS).simulation().latencyMin()).isEqualTo(Duration.ofMillis(50));
            assertThat(adapters.get(Channel.EMAIL).simulation().failureRate()).isEqualTo(0.0);
            assertThat(adapters.get(Channel.WEBHOOK).simulation().failureRate()).isEqualTo(0.0);
        });
    }

    @Test
    void invalidPropertyPreventsStartup() {
        contextRunner.withPropertyValues("notify.simulation.email.failure-rate=2")
                .run(context -> assertThat(context).hasFailed());
    }

    private static Map<Channel, SimulatedChannelAdapter> adaptersByChannel(
            org.springframework.context.ApplicationContext context) {
        Map<String, ChannelAdapter> beans = context.getBeansOfType(ChannelAdapter.class);
        assertThat(beans).hasSize(3);
        return beans.values().stream()
                .map(SimulatedChannelAdapter.class::cast)
                .collect(Collectors.toMap(ChannelAdapter::channel, adapter -> adapter));
    }

    private static SimulatedChannelAdapter adapter(Channel channel, ChannelSimulation simulation) {
        return new SimulatedChannelAdapter(channel, simulation, ThreadLocalRandom::current);
    }

    private static ChannelSimulation simulation(long minMs, long maxMs, double failureRate) {
        return new ChannelSimulation(Duration.ofMillis(minMs), Duration.ofMillis(maxMs), failureRate);
    }
}
