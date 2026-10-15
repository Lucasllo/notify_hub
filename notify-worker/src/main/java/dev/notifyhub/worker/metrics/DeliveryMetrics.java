package dev.notifyhub.worker.metrics;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.FailureClass;
import dev.notifyhub.common.messaging.TopologyProperties;
import dev.notifyhub.worker.failure.FailureClassifier;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Esqueleto só de compilação (RED do 02-06). */
@Component
public class DeliveryMetrics {

    public DeliveryMetrics(MeterRegistry registry, TopologyProperties topology, FailureClassifier classifier) {
    }

    public void sent(Channel channel) {
    }

    public void retrying(Channel channel, int level) {
    }

    public void failed(Channel channel, FailureClass failureClass) {
    }

    public void deadLettered(Channel channel) {
    }

    public void rejected(Channel channel, RejectionReason reason) {
    }

    public void recordAdapterCall(Channel channel, Runnable call) {
        call.run();
    }

    public enum RejectionReason {
        BULKHEAD_FULL,
        CIRCUIT_OPEN
    }
}
