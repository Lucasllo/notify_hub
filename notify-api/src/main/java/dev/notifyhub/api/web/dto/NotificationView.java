package dev.notifyhub.api.web.dto;

import dev.notifyhub.common.domain.AttemptOutcome;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.FailureClass;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.persistence.AttemptEntry;
import dev.notifyhub.common.persistence.LastError;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.common.persistence.RenderedContent;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Representação completa de {@code GET /notifications/{id}} (D-03). */
public record NotificationView(
        String id,
        NotificationStatus status,
        Channel channel,
        String recipient,
        String templateCode,
        Map<String, Object> variables,
        RenderedContent rendered,
        int retryCount,
        LastError lastError,
        List<AttemptView> attempts,
        Instant createdAt,
        Instant updatedAt) {

    /** Uma tentativa de entrega; {@code timestamp} é o início da tentativa. */
    public record AttemptView(
            int number,
            int retryLevel,
            Instant timestamp,
            long durationMs,
            AttemptOutcome outcome,
            String errorCode,
            String errorMessage,
            FailureClass failureClass,
            String thread) {

        static AttemptView from(AttemptEntry entry) {
            return new AttemptView(entry.number(), entry.retryLevel(), entry.startedAt(), entry.durationMs(),
                    entry.outcome(), entry.errorCode(), entry.errorMessage(), entry.failureClass(), entry.thread());
        }
    }

    public static NotificationView from(NotificationDocument doc) {
        List<AttemptView> attempts = doc.attempts() == null
                ? List.of()
                : doc.attempts().stream().map(AttemptView::from).toList();
        return new NotificationView(doc.id(), doc.status(), doc.channel(), doc.recipient(), doc.templateCode(),
                doc.variables(), doc.rendered(), doc.retryCount(), doc.lastError(), attempts,
                doc.createdAt(), doc.updatedAt());
    }
}
