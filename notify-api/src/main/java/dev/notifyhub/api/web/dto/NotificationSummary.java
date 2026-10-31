package dev.notifyhub.api.web.dto;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import java.time.Instant;

/**
 * Item resumido de {@code GET /notifications} (D-11). Leva só o necessário para a visão operacional:
 * nada de {@code variables}, {@code rendered} nem {@code attempts} (conteúdo e PII da notificação), nem
 * {@code idempotencyKey}/{@code requestHash}. O detalhe completo continua no
 * {@code GET /notifications/{id}} ({@link NotificationView}).
 *
 * <p>{@code lastError} é um objeto só com {@code code}, para o caminho JSON {@code lastError.code} ser o
 * mesmo do GET por id. Sem erro, é nulo.
 *
 * <p>Também é o tipo de leitura da consulta projetada da listagem: os componentes têm os nomes dos
 * campos gravados em {@code notifications} ({@code id} ↔ {@code _id}, {@code lastError.code}), e o
 * Spring Data monta o record direto do documento projetado. Por isso todo componente primitivo
 * ({@code retryCount}) precisa estar na projeção.
 */
public record NotificationSummary(
        String id,
        NotificationStatus status,
        Channel channel,
        String recipient,
        String templateCode,
        int retryCount,
        LastErrorSummary lastError,
        Instant createdAt,
        Instant updatedAt) {

    /** Só o código do último erro; mensagem e classe ficam no GET por id. */
    public record LastErrorSummary(String code) {
    }
}
