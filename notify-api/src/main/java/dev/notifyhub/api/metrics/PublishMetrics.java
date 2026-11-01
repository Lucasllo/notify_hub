package dev.notifyhub.api.metrics;

import dev.notifyhub.api.messaging.PublishResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Esqueleto do contador de resultado do publish (RED do 04-02): só as assinaturas, sem comportamento. */
@Component
public class PublishMetrics {

    public static final String OUTCOMES = "notify.publish.outcomes";

    public PublishMetrics(MeterRegistry registry) {
    }

    public void confirmed() {
    }

    public void confirmedByClaim() {
    }

    public void failed(PublishResult.Outcome outcome) {
    }

    public void unconfirmed(long count) {
    }
}
