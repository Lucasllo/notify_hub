package dev.notifyhub.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.notifyhub.api.application.NotificationResetService;
import dev.notifyhub.api.messaging.NotificationPublisher;
import dev.notifyhub.api.messaging.PublishProperties;
import dev.notifyhub.common.domain.Channel;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/** Esqueleto (RED). */
@Service
public class DlqReprocessService {

    public DlqReprocessService(RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin,
            NotificationResetService resetService, NotificationPublisher publisher, ObjectMapper objectMapper,
            PublishProperties props) {
    }

    public Report reprocess(Channel channel, int max) {
        return new Report(max, 0, 0, 0);
    }

    public record Report(int requested, int reprocessed, int skipped, int discarded) {
    }

    public enum FailureReason {
        PUBLISH_NACK,
        PUBLISH_RETURNED,
        PUBLISH_TIMEOUT,
        BROKER_UNAVAILABLE,
        MONGO_UNAVAILABLE,
        DLQ_UNAVAILABLE
    }
}
