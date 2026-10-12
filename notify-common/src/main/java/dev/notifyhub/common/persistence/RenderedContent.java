package dev.notifyhub.common.persistence;

/**
 * Conteúdo renderizado gravado no documento junto com o resultado da entrega (D-03).
 *
 * @param subject assunto do e-mail; nulo para SMS e WEBHOOK
 * @param body    corpo renderizado
 */
public record RenderedContent(String subject, String body) {
}
