package dev.notifyhub.worker.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.persistence.RenderedContent;
import dev.notifyhub.worker.channel.SimulationProperties.ChannelSimulation;
import dev.notifyhub.worker.failure.ErrorCode;
import dev.notifyhub.worker.failure.PermanentDeliveryException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SimulatedChannelAdapterTest {

    private static final RenderedContent CONTENT = new RenderedContent(null, "Olá, Ana!");
    private static final AttemptContext FIRST = new AttemptContext(0, 1);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SimulationConfiguration.class);

    /** Carrega o application.yml real (e o profile ativo), como o worker em produção (WR-07). */
    private final ApplicationContextRunner yamlRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(SimulationConfiguration.class);

    @Test
    void alwaysFailsWhenFailureRateIsOne() {
        SimulatedChannelAdapter adapter = adapter(Channel.SMS, simulation(0, 0, 1.0));

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> adapter.send("+5511999990000", CONTENT, FIRST))
                    .isInstanceOf(SimulatedDeliveryException.class)
                    .hasMessageContaining("sms");
        }
    }

    @Test
    void neverFailsWhenFailureRateIsZero() {
        SimulatedChannelAdapter adapter = adapter(Channel.EMAIL, simulation(0, 0, 0.0));

        for (int i = 0; i < 20; i++) {
            assertThatCode(() -> adapter.send("ana@exemplo.com", CONTENT, FIRST)).doesNotThrowAnyException();
        }
    }

    @Test
    void blocksForAtLeastTheMinimumLatency() {
        SimulatedChannelAdapter adapter = adapter(Channel.WEBHOOK, simulation(30, 30, 0.0));

        long start = System.nanoTime();
        adapter.send("https://hooks.exemplo.com/notify", CONTENT, FIRST);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }

    @Test
    void zeroLatencyReturnsQuickly() {
        SimulatedChannelAdapter adapter = adapter(Channel.EMAIL, simulation(0, 0, 0.0));

        long start = System.nanoTime();
        adapter.send("ana@exemplo.com", CONTENT, FIRST);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(elapsedMs).isLessThan(200);
    }

    @Test
    void interruptedSendRestoresFlagAndFails() {
        SimulatedChannelAdapter adapter = adapter(Channel.EMAIL, simulation(5_000, 5_000, 0.0));

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> adapter.send("ana@exemplo.com", CONTENT, FIRST))
                    .isInstanceOf(SimulatedDeliveryException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    // ------------------------------------------------------------ destinatários mágicos (D-01, D-02, D-04)

    @Test
    void failOneThenOkFailsTransientlyOnTheFirstLevel() {
        SimulatedChannelAdapter adapter = adapter(Channel.EMAIL, simulation(0, 0, 0.0));

        assertThatThrownBy(() -> adapter.send("fail-1-then-ok@fail.test", CONTENT, new AttemptContext(0, 1)))
                .isInstanceOf(SimulatedDeliveryException.class)
                .hasMessageContaining("email")
                .hasMessageNotContaining("fail-1-then-ok");
    }

    @Test
    void failOneThenOkSucceedsOnceRetryCountReachesN() {
        SimulatedChannelAdapter adapter = adapter(Channel.EMAIL, simulation(0, 0, 0.0));

        assertThatCode(() -> adapter.send("fail-1-then-ok@fail.test", CONTENT, new AttemptContext(1, 2)))
                .doesNotThrowAnyException();
    }

    @Test
    void magicFailureIsThrownOnlyAfterTheSimulatedLatency() {
        SimulatedChannelAdapter adapter = adapter(Channel.EMAIL, simulation(50, 50, 0.0));

        long start = System.nanoTime();
        assertThatThrownBy(() -> adapter.send("fail-1-then-ok@fail.test", CONTENT, new AttemptContext(0, 1)))
                .isInstanceOf(SimulatedDeliveryException.class);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(elapsedMs).isGreaterThanOrEqualTo(50);
    }

    @Test
    void alwaysTransientFailsEvenWithFailureRateZero() {
        SimulatedChannelAdapter adapter = adapter(Channel.EMAIL, simulation(0, 0, 0.0));

        for (int h = 0; h <= 3; h++) {
            AttemptContext context = new AttemptContext(h, h + 1);
            assertThatThrownBy(() -> adapter.send("always-transient@fail.test", CONTENT, context))
                    .isInstanceOf(SimulatedDeliveryException.class)
                    .hasMessageContaining("destinatário mágico")
                    .hasMessageNotContaining("always-transient");
        }
    }

    @Test
    void alwaysPermanentThrowsAPermanentSimulatedFailure() {
        SimulatedChannelAdapter adapter = adapter(Channel.EMAIL, simulation(0, 0, 0.0));

        assertThatThrownBy(() -> adapter.send("always-permanent@fail.test", CONTENT, FIRST))
                .isInstanceOfSatisfying(PermanentDeliveryException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SIMULATED_PERMANENT))
                .hasMessageContaining("email")
                .hasMessageNotContaining("always-permanent");
    }

    @Test
    void failUntilReprocessFailsUpToTheFourthAttemptWhateverTheLevel() {
        SimulatedChannelAdapter adapter = adapter(Channel.EMAIL, simulation(0, 0, 0.0));

        assertThatThrownBy(() -> adapter.send("fail-until-reprocess@fail.test", CONTENT, new AttemptContext(3, 4)))
                .isInstanceOf(SimulatedDeliveryException.class);
        assertThatThrownBy(() -> adapter.send("fail-until-reprocess@fail.test", CONTENT, new AttemptContext(0, 1)))
                .isInstanceOf(SimulatedDeliveryException.class);
    }

    @Test
    void failUntilReprocessSucceedsOnTheFifthAttemptAfterAReprocessResetTheLevel() {
        SimulatedChannelAdapter adapter = adapter(Channel.EMAIL, simulation(0, 0, 0.0));

        assertThatCode(() -> adapter.send("fail-until-reprocess@fail.test", CONTENT, new AttemptContext(0, 5)))
                .doesNotThrowAnyException();
    }

    @Test
    void smsAndWebhookMagicRecipientsFollowTheSameScripts() {
        SimulatedChannelAdapter sms = adapter(Channel.SMS, simulation(0, 0, 0.0));
        SimulatedChannelAdapter webhook = adapter(Channel.WEBHOOK, simulation(0, 0, 0.0));

        assertThatThrownBy(() -> sms.send("+550000000002", CONTENT, FIRST))
                .isInstanceOf(PermanentDeliveryException.class);
        assertThatThrownBy(() -> sms.send("+550000000123", CONTENT, new AttemptContext(1, 2)))
                .isInstanceOf(SimulatedDeliveryException.class);
        assertThatCode(() -> sms.send("+550000000123", CONTENT, new AttemptContext(2, 3)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> webhook.send("https://fail.test/always-transient", CONTENT, FIRST))
                .isInstanceOf(SimulatedDeliveryException.class);
        assertThatCode(() -> webhook.send("https://fail.test.evil.com/always-transient", CONTENT, FIRST))
                .doesNotThrowAnyException();
    }

    @Test
    void disabledMagicRecipientsFallBackToTheRandomFailureOnly() {
        SimulatedChannelAdapter alwaysFails = new SimulatedChannelAdapter(
                Channel.EMAIL, simulation(0, 0, 1.0), ThreadLocalRandom::current, false);
        SimulatedChannelAdapter neverFails = new SimulatedChannelAdapter(
                Channel.EMAIL, simulation(0, 0, 0.0), ThreadLocalRandom::current, false);

        assertThatThrownBy(() -> alwaysFails.send("always-transient@fail.test", CONTENT, FIRST))
                .isInstanceOf(SimulatedDeliveryException.class)
                .hasMessage("falha simulada no canal email");
        assertThatCode(() -> neverFails.send("always-transient@fail.test", CONTENT, FIRST))
                .doesNotThrowAnyException();
        assertThatCode(() -> neverFails.send("always-permanent@fail.test", CONTENT, FIRST))
                .doesNotThrowAnyException();
    }

    @Test
    void attemptContextRejectsNegativeLevelAndAttemptBelowOne() {
        assertThatThrownBy(() -> new AttemptContext(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttemptContext(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new AttemptContext(0, 1)).doesNotThrowAnyException();
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
    void magicRecipientsAreDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context.getBean(SimulationProperties.class).magicRecipients()).isFalse();
            SimulatedChannelAdapter email = adaptersByChannel(context).get(Channel.EMAIL);
            assertThatCode(() -> email.send("always-permanent@fail.test", CONTENT, FIRST))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    void magicRecipientsPropertyTurnsTheFeatureOnForEveryAdapter() {
        contextRunner.withPropertyValues("notify.simulation.magic-recipients=true").run(context -> {
            assertThat(context.getBean(SimulationProperties.class).magicRecipients()).isTrue();
            Map<Channel, SimulatedChannelAdapter> adapters = adaptersByChannel(context);
            assertThatThrownBy(() -> adapters.get(Channel.EMAIL).send("always-permanent@fail.test", CONTENT, FIRST))
                    .isInstanceOf(PermanentDeliveryException.class);
            assertThatThrownBy(() -> adapters.get(Channel.SMS).send("+550000000002", CONTENT, FIRST))
                    .isInstanceOf(PermanentDeliveryException.class);
            assertThatThrownBy(() -> adapters.get(Channel.WEBHOOK).send("https://fail.test/always-permanent", CONTENT, FIRST))
                    .isInstanceOf(PermanentDeliveryException.class);
        });
    }

    @Test
    void baseProfileYamlKeepsMagicRecipientsOff() {
        yamlRunner.run(context ->
                assertThat(context.getBean(SimulationProperties.class).magicRecipients()).isFalse());
    }

    @Test
    void demoProfileYamlTurnsMagicRecipientsOn() {
        yamlRunner.withPropertyValues("spring.profiles.active=demo").run(context ->
                assertThat(context.getBean(SimulationProperties.class).magicRecipients()).isTrue());
    }

    @Test
    void magicRecipientsPropertyTurnsTheFeatureOffForEveryAdapter() {
        contextRunner.withPropertyValues("notify.simulation.magic-recipients=false").run(context -> {
            assertThat(context.getBean(SimulationProperties.class).magicRecipients()).isFalse();
            Map<Channel, SimulatedChannelAdapter> adapters = adaptersByChannel(context);
            assertThatCode(() -> adapters.get(Channel.EMAIL).send("always-permanent@fail.test", CONTENT, FIRST))
                    .doesNotThrowAnyException();
            assertThatCode(() -> adapters.get(Channel.SMS).send("+550000000001", CONTENT, FIRST))
                    .doesNotThrowAnyException();
            assertThatCode(() -> adapters.get(Channel.WEBHOOK).send("https://fail.test/always-transient", CONTENT, FIRST))
                    .doesNotThrowAnyException();
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
