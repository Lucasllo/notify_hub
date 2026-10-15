package dev.notifyhub.worker.channel;

import dev.notifyhub.common.domain.Channel;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Destinatários mágicos (DLV-02, D-01): um destinatário reservado escolhe um roteiro de falha
 * determinístico, executado pelo {@link SimulatedChannelAdapter} depois da latência simulada.
 *
 * <p>E-mail: {@code <regra>@fail.test} (domínio exato, sem diferenciar maiúsculas). Regras:
 * {@code always-transient}, {@code always-permanent}, {@code fail-<N>-then-ok} (N de 1 a 99, sem zero
 * à esquerda) e {@code fail-until-reprocess}. Qualquer outro destinatário não é mágico.
 */
public final class MagicRecipients {

    /** Tentativas que {@code fail-until-reprocess} falha: envio inicial + 3 níveis (D-03). */
    public static final int FAIL_UNTIL_REPROCESS_ATTEMPTS = 4;

    private static final String MAGIC_HOST = "fail.test";
    private static final Pattern FAIL_N_THEN_OK = Pattern.compile("fail-([1-9][0-9]?)-then-ok");

    private MagicRecipients() {
    }

    /**
     * @return o roteiro mágico do destinatário, ou vazio se ele não casa exatamente com um padrão
     *         reservado do canal
     */
    public static Optional<MagicBehavior> parse(Channel channel, String recipient) {
        if (channel == null || recipient == null || recipient.isEmpty()) {
            return Optional.empty();
        }
        return switch (channel) {
            case EMAIL -> parseEmail(recipient);
            case SMS, WEBHOOK -> Optional.empty();
        };
    }

    private static Optional<MagicBehavior> parseEmail(String recipient) {
        int at = recipient.lastIndexOf('@');
        if (at <= 0) {
            return Optional.empty();
        }
        String domain = recipient.substring(at + 1);
        if (!domain.equalsIgnoreCase(MAGIC_HOST)) {
            return Optional.empty();
        }
        return parseRule(recipient.substring(0, at));
    }

    private static Optional<MagicBehavior> parseRule(String rule) {
        String normalized = rule.toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "always-transient":
                return Optional.of(new MagicBehavior(Kind.ALWAYS_TRANSIENT, 0));
            case "always-permanent":
                return Optional.of(new MagicBehavior(Kind.ALWAYS_PERMANENT, 0));
            case "fail-until-reprocess":
                return Optional.of(new MagicBehavior(Kind.FAIL_UNTIL_REPROCESS, FAIL_UNTIL_REPROCESS_ATTEMPTS));
            default:
                Matcher m = FAIL_N_THEN_OK.matcher(normalized);
                return m.matches()
                        ? Optional.of(new MagicBehavior(Kind.FAIL_N_THEN_OK, Integer.parseInt(m.group(1))))
                        : Optional.empty();
        }
    }

    /** Roteiros mágicos. */
    public enum Kind { ALWAYS_TRANSIENT, ALWAYS_PERMANENT, FAIL_N_THEN_OK, FAIL_UNTIL_REPROCESS }

    /**
     * @param kind     roteiro
     * @param failures quantas vezes falha antes de suceder ({@code fail-<N>-then-ok}: N;
     *                 {@code fail-until-reprocess}: {@link #FAIL_UNTIL_REPROCESS_ATTEMPTS}; demais: 0)
     */
    public record MagicBehavior(Kind kind, int failures) {
    }
}
