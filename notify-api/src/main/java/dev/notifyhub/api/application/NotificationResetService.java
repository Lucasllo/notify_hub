package dev.notifyhub.api.application;

import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.persistence.NotificationDocument;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/** Esqueleto (RED). */
@Service
public class NotificationResetService {

    private final MongoTemplate mongo;

    public NotificationResetService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public ResetResult resetForReprocess(String id) {
        return new Missing();
    }

    public sealed interface ResetResult permits Reset, Missing, Sent, NotResettable, FenceLost {
    }

    public record Reset(NotificationDocument document, NotificationStatus previousStatus) implements ResetResult {
    }

    public record Missing() implements ResetResult {
    }

    public record Sent() implements ResetResult {
    }

    public record NotResettable(NotificationStatus status) implements ResetResult {
    }

    public record FenceLost() implements ResetResult {
    }
}
