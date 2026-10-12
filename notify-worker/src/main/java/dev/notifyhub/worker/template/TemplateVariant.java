package dev.notifyhub.worker.template;

/**
 * Variante de um template para um canal.
 *
 * @param subject assunto (só EMAIL; nulo em SMS e WEBHOOK)
 * @param body    corpo Mustache
 */
public record TemplateVariant(String subject, String body) {
}
