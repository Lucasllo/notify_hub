package dev.notifyhub.worker.listener;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rabbitmq.client.Channel;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.messaging.NotifyHeaders;
import dev.notifyhub.worker.processing.DeliveryProcessor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.adapter.HandlerAdapter;
import org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;

/**
 * Os métodos de listener chamados pelo mecanismo real do Spring AMQP (adapter, resolvers de
 * argumento e conversor JSON), com uma mensagem AMQP como a que a API publica. Pega erros de
 * assinatura que a chamada direta não pega: um parâmetro {@code Object} recebe a {@link Message}
 * inteira em vez do header, porque o Spring AMQP casa "argumentos fornecidos" por tipo antes dos
 * resolvers.
 */
class ChannelListenersDispatchTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private final DeliveryProcessor processor = mock(DeliveryProcessor.class);
    private final ChannelListeners listeners = new ChannelListeners(processor);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private MessagingMessageListenerAdapter adapterFor(String methodName) {
        Method method = Arrays.stream(ChannelListeners.class.getMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        DefaultMessageHandlerMethodFactory factory = new DefaultMessageHandlerMethodFactory();
        factory.afterPropertiesSet();
        MessagingMessageListenerAdapter adapter = new MessagingMessageListenerAdapter(listeners, method);
        adapter.setHandlerAdapter(new HandlerAdapter(factory.createInvocableHandlerMethod(listeners, method)));
        adapter.setMessageConverter(new Jackson2JsonMessageConverter(json));
        return adapter;
    }

    private Message amqpMessage(NotificationMessage payload, Object retryHeader) throws Exception {
        return amqpMessage(payload, retryHeader, false);
    }

    private Message amqpMessage(NotificationMessage payload, Object retryHeader, boolean redelivered)
            throws Exception {
        MessageProperties props = new MessageProperties();
        props.setRedelivered(redelivered);
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("__TypeId__", NotificationMessage.class.getName());
        if (retryHeader != null) {
            props.setHeader(NotifyHeaders.RETRY_COUNT, retryHeader);
        }
        return new Message(json.writeValueAsBytes(payload), props);
    }

    @Test
    void integerHeaderPublishedByTheApiReachesTheProcessor() throws Exception {
        NotificationMessage payload = new NotificationMessage(
                "n-1", dev.notifyhub.common.domain.Channel.EMAIL, "welcome", NOW);

        adapterFor("onEmail").onMessage(amqpMessage(payload, 0), mock(Channel.class));

        verify(processor).process(dev.notifyhub.common.domain.Channel.EMAIL, payload, 0, false);
    }

    @Test
    void stringHeaderPublishedFromTheManagementUiReachesTheProcessor() throws Exception {
        NotificationMessage payload = new NotificationMessage(
                "n-2", dev.notifyhub.common.domain.Channel.SMS, "welcome", NOW);

        adapterFor("onSms").onMessage(amqpMessage(payload, " 2 "), mock(Channel.class));

        verify(processor).process(dev.notifyhub.common.domain.Channel.SMS, payload, 2, false);
    }

    @Test
    void absentHeaderIsLevelZero() throws Exception {
        NotificationMessage payload = new NotificationMessage(
                "n-3", dev.notifyhub.common.domain.Channel.WEBHOOK, "welcome", NOW);

        adapterFor("onWebhook").onMessage(amqpMessage(payload, null), mock(Channel.class));

        verify(processor).process(dev.notifyhub.common.domain.Channel.WEBHOOK, payload, 0, false);
    }

    @Test
    void brokerRedeliveryFlagReachesTheProcessorAsTrue() throws Exception {
        NotificationMessage payload = new NotificationMessage(
                "n-5", dev.notifyhub.common.domain.Channel.EMAIL, "welcome", NOW);

        adapterFor("onEmail").onMessage(amqpMessage(payload, 1, true), mock(Channel.class));

        verify(processor).process(dev.notifyhub.common.domain.Channel.EMAIL, payload, 1, true);
    }

    @Test
    void firstDeliveryReachesTheProcessorAsNotRedelivered() throws Exception {
        NotificationMessage payload = new NotificationMessage(
                "n-6", dev.notifyhub.common.domain.Channel.SMS, "welcome", NOW);

        adapterFor("onSms").onMessage(amqpMessage(payload, "2", false), mock(Channel.class));

        verify(processor).process(dev.notifyhub.common.domain.Channel.SMS, payload, 2, false);
    }

    @Test
    void webhookRedeliveryWithoutRetryHeaderIsLevelZeroAndRedelivered() throws Exception {
        NotificationMessage payload = new NotificationMessage(
                "n-7", dev.notifyhub.common.domain.Channel.WEBHOOK, "welcome", NOW);

        adapterFor("onWebhook").onMessage(amqpMessage(payload, null, true), mock(Channel.class));

        verify(processor).process(dev.notifyhub.common.domain.Channel.WEBHOOK, payload, 0, true);
    }

    @Test
    void nonNumericHeaderIsRejectedWithoutRequeueAndNeverProcessed() throws Exception {
        NotificationMessage payload = new NotificationMessage(
                "n-4", dev.notifyhub.common.domain.Channel.EMAIL, "welcome", NOW);

        assertThatThrownBy(() -> adapterFor("onEmail").onMessage(amqpMessage(payload, "abc"), mock(Channel.class)))
                .hasRootCauseInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(processor, never()).process(eq(dev.notifyhub.common.domain.Channel.EMAIL), eq(payload), anyInt(),
                anyBoolean());
    }
}
