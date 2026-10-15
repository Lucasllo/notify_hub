package dev.notifyhub.worker.resilience;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.messaging.TopologyProperties;
import dev.notifyhub.common.persistence.RenderedContent;
import dev.notifyhub.worker.channel.AttemptContext;
import dev.notifyhub.worker.channel.ChannelAdapter;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Esqueleto (RED): chama o adapter direto, sem bulkhead nem circuit breaker. */
@Component
public class ChannelGateway {

    private final Map<Channel, ChannelAdapter> adapters = new EnumMap<>(Channel.class);

    public ChannelGateway(List<ChannelAdapter> adapters, CircuitBreakerRegistry circuitBreakerRegistry,
                          BulkheadRegistry bulkheadRegistry, TopologyProperties topology) {
        for (ChannelAdapter adapter : adapters) {
            this.adapters.put(adapter.channel(), adapter);
        }
    }

    public void send(Channel channel, String recipient, RenderedContent content, AttemptContext context) {
        adapters.get(channel).send(recipient, content, context);
    }
}
