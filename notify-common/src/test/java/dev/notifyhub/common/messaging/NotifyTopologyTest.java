package dev.notifyhub.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

class NotifyTopologyTest {

    private static final TopologyProperties DEFAULT_LEVELS = new TopologyProperties(
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(25), Duration.ofSeconds(125)));

    private final Declarables declarables = NotifyTopology.declarables(DEFAULT_LEVELS);

    private Map<String, Queue> queuesByName(Declarables d) {
        return d.getDeclarablesByType(Queue.class).stream()
                .collect(Collectors.toMap(Queue::getName, Function.identity()));
    }

    private Map<String, Exchange> exchangesByName() {
        return declarables.getDeclarablesByType(Exchange.class).stream()
                .collect(Collectors.toMap(Exchange::getName, Function.identity()));
    }

    private List<Binding> bindings() {
        return declarables.getDeclarablesByType(Binding.class);
    }

    @Test
    void declaresThreeDurableExchangesWithExpectedTypes() {
        Map<String, Exchange> exchanges = exchangesByName();

        assertThat(exchanges).hasSize(3);
        assertThat(exchanges.get("notify.exchange")).isInstanceOf(TopicExchange.class);
        assertThat(exchanges.get("notify.retry")).isInstanceOf(DirectExchange.class);
        assertThat(exchanges.get("notify.dlx")).isInstanceOf(DirectExchange.class);
        assertThat(exchanges.values()).allMatch(Exchange::isDurable);
        assertThat(NotifyTopology.MAIN_EXCHANGE).isEqualTo("notify.exchange");
        assertThat(NotifyTopology.RETRY_EXCHANGE).isEqualTo("notify.retry");
        assertThat(NotifyTopology.DLX_EXCHANGE).isEqualTo("notify.dlx");
    }

    @Test
    void declaresFifteenQueuesAndFifteenBindings() {
        assertThat(declarables.getDeclarablesByType(Queue.class)).hasSize(15);
        assertThat(bindings()).hasSize(15);
        assertThat(queuesByName(declarables)).containsOnlyKeys(
                "notify.email", "notify.email.dlq",
                "notify.email.retry.5s", "notify.email.retry.25s", "notify.email.retry.125s",
                "notify.sms", "notify.sms.dlq",
                "notify.sms.retry.5s", "notify.sms.retry.25s", "notify.sms.retry.125s",
                "notify.webhook", "notify.webhook.dlq",
                "notify.webhook.retry.5s", "notify.webhook.retry.25s", "notify.webhook.retry.125s");
    }

    @Test
    void mainQueueDeadLettersToDlx() {
        Queue main = queuesByName(declarables).get("notify.email");

        assertThat(main.getArguments())
                .containsEntry("x-dead-letter-exchange", "notify.dlx")
                .containsEntry("x-dead-letter-routing-key", "email.dlq")
                .hasSize(2);
    }

    @Test
    void retryQueueHasIntegerTtlAndDeadLettersBackToMainExchange() {
        Queue delay = queuesByName(declarables).get("notify.email.retry.25s");

        Object ttl = delay.getArguments().get("x-message-ttl");
        assertThat(ttl).isInstanceOf(Integer.class).isEqualTo(25000);
        assertThat(delay.getArguments())
                .containsEntry("x-dead-letter-exchange", "notify.exchange")
                .containsEntry("x-dead-letter-routing-key", "notification.email")
                .hasSize(3);
    }

    @Test
    void retryTtlsMatchLevelsInMilliseconds() {
        Map<String, Queue> queues = queuesByName(declarables);

        assertThat(queues.get("notify.sms.retry.5s").getArguments().get("x-message-ttl")).isEqualTo(5000);
        assertThat(queues.get("notify.sms.retry.125s").getArguments().get("x-message-ttl")).isEqualTo(125000);
    }

    @Test
    void dlqHasNoArguments() {
        assertThat(queuesByName(declarables).get("notify.email.dlq").getArguments()).isEmpty();
    }

    @Test
    void everyQueueIsDurableNonExclusiveAndNotAutoDelete() {
        assertThat(declarables.getDeclarablesByType(Queue.class)).allSatisfy(q -> {
            assertThat(q.isDurable()).as("durable %s", q.getName()).isTrue();
            assertThat(q.isExclusive()).as("exclusive %s", q.getName()).isFalse();
            assertThat(q.isAutoDelete()).as("autoDelete %s", q.getName()).isFalse();
            assertThat(q.getArguments()).doesNotContainKeys("x-queue-mode", "x-queue-version");
        });
    }

    @Test
    void bindingsUseChannelRoutingKeys() {
        assertThat(bindings()).anySatisfy(b -> {
            assertThat(b.getDestination()).isEqualTo("notify.sms.retry.5s");
            assertThat(b.getExchange()).isEqualTo("notify.retry");
            assertThat(b.getRoutingKey()).isEqualTo("sms.retry.5s");
            assertThat(b.isDestinationQueue()).isTrue();
        });
        assertThat(bindings()).anySatisfy(b -> {
            assertThat(b.getDestination()).isEqualTo("notify.webhook");
            assertThat(b.getExchange()).isEqualTo("notify.exchange");
            assertThat(b.getRoutingKey()).isEqualTo("notification.webhook");
        });
        assertThat(bindings()).anySatisfy(b -> {
            assertThat(b.getDestination()).isEqualTo("notify.email.dlq");
            assertThat(b.getExchange()).isEqualTo("notify.dlx");
            assertThat(b.getRoutingKey()).isEqualTo("email.dlq");
        });
    }

    @Test
    void eachRoutingKeyIsBoundOncePerExchange() {
        Set<String> seen = new HashSet<>();
        for (Binding b : bindings()) {
            assertThat(seen.add(b.getExchange() + "|" + b.getRoutingKey()))
                    .as("chave duplicada %s em %s", b.getRoutingKey(), b.getExchange())
                    .isTrue();
        }
    }

    @Test
    void declaringTwiceProducesIdenticalQueues() {
        Map<String, Queue> first = queuesByName(NotifyTopology.declarables(DEFAULT_LEVELS));
        Map<String, Queue> second = queuesByName(NotifyTopology.declarables(DEFAULT_LEVELS));

        assertThat(second.keySet()).isEqualTo(first.keySet());
        first.forEach((name, q) -> {
            Queue other = second.get(name);
            assertThat(other.getArguments()).as("argumentos de %s", name).isEqualTo(q.getArguments());
            assertThat(other.isDurable()).isEqualTo(q.isDurable());
        });
    }
}
