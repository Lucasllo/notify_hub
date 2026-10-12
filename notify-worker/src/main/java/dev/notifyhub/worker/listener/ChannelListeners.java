package dev.notifyhub.worker.listener;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.messaging.NotifyHeaders;
import dev.notifyhub.worker.processing.DeliveryProcessor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Um container {@code @RabbitListener} por canal (WRK-01), cada um na fila principal do canal e com
 * concorrência própria. O nome da fila vem de {@link Channel#queue()} (D-05).
 *
 * <p>Os containers usam a factory do Boot: com {@code spring.threads.virtual.enabled=true} cada
 * consumidor roda numa virtual thread (WRK-05). Não juntar as filas num único listener: um canal
 * lento travaria os outros.
 *
 * <p>O corpo é desserializado para o tipo concreto {@link NotificationMessage} (T-01-25). Exceções
 * sobem para o container, que rejeita sem requeue e a mensagem vai para a DLQ do canal.
 */
@Component
public class ChannelListeners {

    private final DeliveryProcessor processor;

    public ChannelListeners(DeliveryProcessor processor) {
        this.processor = processor;
    }

    @RabbitListener(id = "listener-email",
            queues = "#{T(dev.notifyhub.common.domain.Channel).EMAIL.queue()}",
            concurrency = "${notify.worker.channels.email.concurrency:10}")
    public void onEmail(NotificationMessage message,
                        @Header(name = NotifyHeaders.RETRY_COUNT, required = false) Object retryCount) {
        processor.process(Channel.EMAIL, message, RetryHeaders.parse(retryCount));
    }
}
