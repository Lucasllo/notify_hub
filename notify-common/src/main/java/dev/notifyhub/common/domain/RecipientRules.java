package dev.notifyhub.common.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Regras de destinatário por canal, num único lugar (validação na API e mascaramento em logs).
 * Formatos simples e documentados; aceitam os destinatários "mágicos" da Fase 2
 * ({@code always-transient@fail.test}, {@code +5500000000001}, {@code https://fail.test/...}).
 *
 * <p>O tamanho é conferido antes da regex, para não gastar regex com entradas enormes.
 */
public final class RecipientRules {

    static final int EMAIL_MAX_LENGTH = 254;
    static final int SMS_MAX_LENGTH = 16;
    static final int WEBHOOK_MAX_LENGTH = 2048;

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final String MASK = "***";

    private RecipientRules() {
    }

    /** Diz se {@code recipient} é um destinatário válido para {@code channel}. */
    public static boolean isValid(Channel channel, String recipient) {
        if (channel == null || recipient == null || recipient.isEmpty()) {
            return false;
        }
        return switch (channel) {
            case EMAIL -> recipient.length() <= EMAIL_MAX_LENGTH && EMAIL.matcher(recipient).matches();
            case SMS -> recipient.length() <= SMS_MAX_LENGTH && E164.matcher(recipient).matches();
            case WEBHOOK -> recipient.length() <= WEBHOOK_MAX_LENGTH && parseWebhook(recipient) != null;
        };
    }

    /** Dica de formato em pt-BR, usada nas mensagens de erro de validação. */
    public static String describe(Channel channel) {
        return switch (channel) {
            case EMAIL -> "e-mail no formato local@dominio.tld, com até " + EMAIL_MAX_LENGTH + " caracteres";
            case SMS -> "SMS no formato E.164, por exemplo +5511999998888";
            case WEBHOOK -> "URL absoluta http ou https, com até " + WEBHOOK_MAX_LENGTH + " caracteres";
        };
    }

    /**
     * Versão segura para logs: nunca devolve o destinatário completo. Entrada inválida vira {@code "***"}.
     */
    public static String mask(Channel channel, String recipient) {
        if (!isValid(channel, recipient)) {
            return MASK;
        }
        return switch (channel) {
            case EMAIL -> {
                int at = recipient.lastIndexOf('@');
                yield recipient.charAt(0) + MASK + recipient.substring(at);
            }
            case SMS -> "+" + recipient.substring(1, 3) + "*****" + recipient.substring(recipient.length() - 2);
            case WEBHOOK -> {
                URI uri = parseWebhook(recipient);
                yield uri.getScheme() + "://" + uri.getHost() + "/" + MASK;
            }
        };
    }

    /** URI absoluta http/https com host; {@code null} se não for. */
    private static URI parseWebhook(String recipient) {
        try {
            URI uri = new URI(recipient);
            if (!uri.isAbsolute() || uri.getHost() == null) {
                return null;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) ? uri : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
