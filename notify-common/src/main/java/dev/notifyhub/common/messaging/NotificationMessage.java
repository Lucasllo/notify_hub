package dev.notifyhub.common.messaging;

import dev.notifyhub.common.domain.Channel;
import java.time.Instant;

/**
 * Mensagem AMQP publicada pela API e consumida pelo worker. É um token fino de propósito:
 * destinatário, variáveis e conteúdo renderizado são PII e ficam só no documento do Mongo,
 * que é a fonte da verdade. Não adicione campos aqui sem rever essa decisão.
 */
public record NotificationMessage(String notificationId, Channel channel, String templateCode, Instant createdAt) {
}
