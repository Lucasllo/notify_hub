package dev.notifyhub.worker.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.worker.processing.DeliveryProcessor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/** Um container por canal (WRK-01): ids, fila derivada de {@link Channel} e concorrência por propriedade. */
class ChannelListenersTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private final DeliveryProcessor processor = mock(DeliveryProcessor.class);
    private final ChannelListeners listeners = new ChannelListeners(processor);

    private static Map<String, RabbitListener> listenersById() {
        return Arrays.stream(ChannelListeners.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(RabbitListener.class))
                .filter(a -> a != null)
                .collect(Collectors.toMap(RabbitListener::id, Function.identity()));
    }

    /** Avalia a expressão {@code #{...}} do atributo {@code queues} como o Spring faria. */
    private static String resolveQueue(String expression) {
        return new SpelExpressionParser()
                .parseExpression(expression, new TemplateParserContext())
                .getValue(String.class);
    }

    @Test
    void thereIsExactlyOneListenerContainerPerChannel() {
        long annotated = Arrays.stream(ChannelListeners.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(RabbitListener.class))
                .count();

        assertThat(annotated).isEqualTo(3);
        assertThat(listenersById()).containsOnlyKeys("listener-email", "listener-sms", "listener-webhook");
    }

    @Test
    void eachListenerConsumesOnlyTheMainQueueOfItsChannel() {
        Map<String, RabbitListener> byId = listenersById();
        for (Channel channel : Channel.values()) {
            RabbitListener listener = byId.get("listener-" + channel.id());

            assertThat(listener.queues()).hasSize(1);
            assertThat(listener.queues()[0])
                    .isEqualTo("#{T(dev.notifyhub.common.domain.Channel)." + channel.name() + ".queue()}");
            assertThat(resolveQueue(listener.queues()[0])).isEqualTo(channel.queue());
        }
    }

    @Test
    void concurrencyComesFromThePerChannelPropertyWithDefaultTen() {
        Map<String, RabbitListener> byId = listenersById();
        for (Channel channel : Channel.values()) {
            String key = channel.name().toLowerCase(Locale.ROOT);

            assertThat(byId.get("listener-" + channel.id()).concurrency())
                    .isEqualTo("${notify.worker.channels." + key + ".concurrency:10}");
        }
    }

    @Test
    void listenersDoNotOverrideTheBootContainerFactory() {
        List<String> factories = listenersById().values().stream()
                .map(RabbitListener::containerFactory)
                .toList();

        assertThat(factories).allMatch(String::isEmpty);
    }

    @Test
    void smsListenerParsesStringHeaderFromTheManagementUi() {
        NotificationMessage message = new NotificationMessage("n-2", Channel.SMS, "welcome", NOW);

        listeners.onSms(message, "1", null);

        verify(processor).process(Channel.SMS, message, 1, false);
    }

    @Test
    void webhookListenerTreatsAbsentHeaderAsLevelZero() {
        NotificationMessage message = new NotificationMessage("n-3", Channel.WEBHOOK, "welcome", NOW);

        listeners.onWebhook(message, null, null);

        verify(processor).process(Channel.WEBHOOK, message, 0, false);
    }

    @Test
    void emailListenerPassesNumericHeader() {
        NotificationMessage message = new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW);

        listeners.onEmail(message, "2", null);

        verify(processor).process(Channel.EMAIL, message, 2, false);
    }

    @Test
    void absentRedeliveredFlagIsFalse() {
        NotificationMessage message = new NotificationMessage("n-2", Channel.SMS, "welcome", NOW);

        listeners.onSms(message, "1", null);

        verify(processor).process(Channel.SMS, message, 1, false);
    }

    @Test
    void redeliveredFlagReachesTheProcessor() {
        NotificationMessage message = new NotificationMessage("n-1", Channel.EMAIL, "welcome", NOW);

        listeners.onEmail(message, null, true);

        verify(processor).process(Channel.EMAIL, message, 0, true);
    }

    @Test
    void webhookListenerPassesTheRedeliveredFlagToo() {
        NotificationMessage message = new NotificationMessage("n-3", Channel.WEBHOOK, "welcome", NOW);

        listeners.onWebhook(message, "2", Boolean.TRUE);

        verify(processor).process(Channel.WEBHOOK, message, 2, true);
    }

    @Test
    void everyListenerReadsTheRedeliveredHeaderAsAnOptionalBoolean() throws NoSuchMethodException {
        for (String name : List.of("onEmail", "onSms", "onWebhook")) {
            Method method = ChannelListeners.class.getMethod(name, NotificationMessage.class, String.class,
                    Boolean.class);
            Parameter redelivered = method.getParameters()[2];

            assertThat(redelivered.getType()).isEqualTo(Boolean.class);
            Header header = redelivered.getAnnotation(Header.class);
            assertThat(header).as("%s: @Header no parâmetro redelivered", name).isNotNull();
            assertThat(header.name()).isEqualTo(AmqpHeaders.REDELIVERED);
            assertThat(header.required()).isFalse();
        }
    }

    @Test
    void listenerMethodsTakeTheConcreteMessageType() throws NoSuchMethodException {
        for (String name : List.of("onEmail", "onSms", "onWebhook")) {
            Method method = ChannelListeners.class.getMethod(name, NotificationMessage.class, String.class, Boolean.class);
            assertThat(method.getParameterTypes()[0]).isEqualTo(NotificationMessage.class);
        }
    }
}
