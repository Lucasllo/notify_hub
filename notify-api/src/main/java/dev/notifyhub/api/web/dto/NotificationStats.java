package dev.notifyhub.api.web.dto;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import java.util.Map;

/**
 * Contagens de {@code GET /notifications/stats} (API-07, D-12):
 * {@code {total, byStatus, byChannel, byChannelAndStatus}}.
 *
 * <p>Os 7 status e os 3 canais aparecem sempre, inclusive com zero; {@code byChannelAndStatus} tem as
 * 3 × 7 combinações. Os mapas são {@code EnumMap}, na ordem de declaração dos enums, e o Jackson
 * serializa as chaves pelo nome ({@code EMAIL}, {@code SENT}). Este formato é um contrato: o README e
 * o resumo do k6 da Fase 4 leem estas chaves.
 *
 * @param total              total de notificações contadas
 * @param byStatus           contagem por status
 * @param byChannel          contagem por canal
 * @param byChannelAndStatus contagem por canal e, dentro dele, por status
 */
public record NotificationStats(
        long total,
        Map<NotificationStatus, Long> byStatus,
        Map<Channel, Long> byChannel,
        Map<Channel, Map<NotificationStatus, Long>> byChannelAndStatus) {
}
