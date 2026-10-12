package dev.notifyhub.api.web.dto;

import dev.notifyhub.common.domain.NotificationStatus;
import java.time.Instant;

/** Corpo mínimo do 202 (D-02); o {@code Location} aponta para a representação completa. */
public record CreateNotificationResponse(String id, NotificationStatus status, Instant createdAt) {
}
