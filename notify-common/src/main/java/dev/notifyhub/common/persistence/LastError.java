package dev.notifyhub.common.persistence;

import dev.notifyhub.common.domain.FailureClass;
import java.time.Instant;

/**
 * Último erro registrado na notificação ({@code notifications.lastError}).
 *
 * @param code         código do erro (ex.: {@code TIMEOUT}, {@code PUBLISH_NACK})
 * @param message      mensagem legível
 * @param failureClass classe da falha; pode ser nula para erros de publicação
 * @param at           momento do erro
 */
public record LastError(String code, String message, FailureClass failureClass, Instant at) {
}
