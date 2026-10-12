package dev.notifyhub.common.messaging;

import dev.notifyhub.common.domain.Channel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * Topologia RabbitMQ completa do Notify Hub (MSG-01..05). Este é o único lugar do projeto com
 * argumentos de fila: mudar um argumento de uma fila já declarada faz o broker responder
 * {@code 406 PRECONDITION_FAILED}, então API e worker declaram sempre a partir daqui.
 *
 * <ul>
 *   <li>{@code notify.exchange} (topic): fila principal {@code notify.<canal>} pela chave
 *       {@code notification.<canal>}; a fila faz dead-letter para {@code notify.dlx}.</li>
 *   <li>{@code notify.retry} (direct): filas de delay {@code notify.<canal>.retry.<ttl>}, com TTL
 *       por fila e dead-letter de volta para {@code notify.exchange}.</li>
 *   <li>{@code notify.dlx} (direct): DLQ final {@code notify.<canal>.dlq}, sem argumentos.</li>
 * </ul>
 *
 * <p>Só filas duráveis ({@link QueueBuilder#durable}) e nenhum argumento de modo ou versão de fila:
 * o RabbitMQ 4.3 recusa filas transientes não exclusivas e argumentos de CQv1.
 */
public final class NotifyTopology {

    public static final String MAIN_EXCHANGE = "notify.exchange";
    public static final String RETRY_EXCHANGE = "notify.retry";
    public static final String DLX_EXCHANGE = "notify.dlx";

    private NotifyTopology() {
    }

    /** Monta exchanges, filas e bindings de todos os canais para os níveis informados. */
    public static Declarables declarables(TopologyProperties props) {
        TopicExchange main = ExchangeBuilder.topicExchange(MAIN_EXCHANGE).durable(true).build();
        DirectExchange retry = ExchangeBuilder.directExchange(RETRY_EXCHANGE).durable(true).build();
        DirectExchange dlx = ExchangeBuilder.directExchange(DLX_EXCHANGE).durable(true).build();

        List<Declarable> declarables = new ArrayList<>(List.of(main, retry, dlx));
        for (Channel ch : Channel.values()) {
            Queue queue = QueueBuilder.durable(ch.queue())
                    .deadLetterExchange(DLX_EXCHANGE)
                    .deadLetterRoutingKey(ch.dlqKey())
                    .build();
            Queue dlq = QueueBuilder.durable(ch.dlq()).build();
            declarables.add(queue);
            declarables.add(dlq);
            declarables.add(BindingBuilder.bind(queue).to(main).with(ch.routingKey()));
            declarables.add(BindingBuilder.bind(dlq).to(dlx).with(ch.dlqKey()));

            for (Duration ttl : props.retryLevels()) {
                Queue delay = QueueBuilder.durable(ch.retryQueue(ttl))
                        .ttl(Math.toIntExact(ttl.toMillis()))
                        .deadLetterExchange(MAIN_EXCHANGE)
                        .deadLetterRoutingKey(ch.routingKey())
                        .build();
                declarables.add(delay);
                declarables.add(BindingBuilder.bind(delay).to(retry).with(ch.retryKey(ttl)));
            }
        }
        return new Declarables(declarables);
    }
}
