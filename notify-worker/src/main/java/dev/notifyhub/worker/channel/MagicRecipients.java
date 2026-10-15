package dev.notifyhub.worker.channel;

import dev.notifyhub.common.domain.Channel;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Destinatários mágicos (DLV-02, D-01): um destinatário reservado escolhe um roteiro de falha
 * determinístico, executado pelo {@link SimulatedChannelAdapter} depois da latência simulada e com as
 * mesmas exceções de uma falha real do provedor (D-04). É um recurso de demo e de teste; a flag
 * {@code notify.simulation.magic-recipients} desliga o reconhecimento.
 *
 * <p>Tabela completa (contrato dos ITs da Fase 3, do k6 da Fase 4 e do README):
 *
 * <table>
 *   <caption>Destinatários mágicos por canal</caption>
 *   <tr><th>Roteiro</th><th>E-mail</th><th>Webhook</th><th>SMS</th></tr>
 *   <tr><td>sempre transitória</td><td>{@code always-transient@fail.test}</td>
 *       <td>{@code https://fail.test/always-transient}</td><td>{@code +550000000001}</td></tr>
 *   <tr><td>sempre permanente</td><td>{@code always-permanent@fail.test}</td>
 *       <td>{@code https://fail.test/always-permanent}</td><td>{@code +550000000002}</td></tr>
 *   <tr><td>falha N vezes e depois sucede</td><td>{@code fail-<N>-then-ok@fail.test} (N de 1 a 99)</td>
 *       <td>{@code https://fail.test/fail-<N>-then-ok} (N de 1 a 99)</td>
 *       <td>{@code +5500000001N3} (N de 1 a 9; ex.: {@code +550000000123} falha 2 vezes)</td></tr>
 *   <tr><td>falha até ser reprocessada</td><td>{@code fail-until-reprocess@fail.test}</td>
 *       <td>{@code https://fail.test/fail-until-reprocess}</td><td>{@code +550000000004}</td></tr>
 * </table>
 *
 * <p>Regras de casamento, sempre exatas (T-02-17): no e-mail, o domínio depois do último {@code @} é
 * {@code fail.test}; no webhook, o esquema é http/https, o host é {@code fail.test} e a regra é o
 * primeiro segmento do path; domínio, host e regra não diferenciam maiúsculas. N não aceita zero à
 * esquerda. No SMS, o destinatário tem exatamente 13 caracteres e um dos códigos acima. Qualquer outro
 * destinatário, inclusive parecidos como {@code always-transient@fail.test.com.br} ou
 * {@code https://fail.test.evil.com/always-transient}, segue o fluxo normal (latência +
 * {@code failure-rate}). O TLD {@code .test} é reservado (RFC 2606) e {@code +55 00…} não é número
 * real; de qualquer forma, nenhum envio sai da máquina.
 *
 * <p><b>{@code fail-<N>-then-ok} (D-02)</b> falha enquanto {@code retryCount < N}, sem guardar estado:
 * com 3 níveis, N ≤ 3 termina SENT e N ≥ 4 termina DEAD_LETTERED. Um reprocessamento zera
 * {@code retryCount}, então a notificação reprocessada volta a falhar N vezes.
 *
 * <p><b>{@code fail-until-reprocess} (D-03)</b> falha enquanto {@code attemptNumber ≤ }
 * {@value #FAIL_UNTIL_REPROCESS_ATTEMPTS} (envio inicial + 3 níveis) e sucede a partir da 5ª
 * tentativa, que só existe depois de um reprocessamento, porque o reprocessamento não zera
 * {@code attemptCount}. Toda tentativa conta, inclusive recusas do circuit breaker e do bulkhead
 * (D-14). O limite é fixo, mesmo com outro número de níveis em {@code notify.topology.retry-levels}.
 */
public final class MagicRecipients {

    /** Tentativas que {@code fail-until-reprocess} falha: envio inicial + 3 níveis (D-03). */
    public static final int FAIL_UNTIL_REPROCESS_ATTEMPTS = 4;

    private static final String MAGIC_HOST = "fail.test";
    private static final String SMS_PREFIX = "+550000000";
    private static final int SMS_LENGTH = SMS_PREFIX.length() + 3;
    private static final Pattern FAIL_N_THEN_OK = Pattern.compile("fail-([1-9][0-9]?)-then-ok");
    private static final Pattern SMS_FAIL_N_THEN_OK = Pattern.compile("1([1-9])3");

    private MagicRecipients() {
    }

    /**
     * @return o roteiro mágico do destinatário, ou vazio se ele não casa exatamente com um padrão
     *         reservado do canal; nunca lança
     */
    public static Optional<MagicBehavior> parse(Channel channel, String recipient) {
        if (channel == null || recipient == null || recipient.isEmpty()) {
            return Optional.empty();
        }
        return switch (channel) {
            case EMAIL -> parseEmail(recipient);
            case WEBHOOK -> parseWebhook(recipient);
            case SMS -> parseSms(recipient);
        };
    }

    private static Optional<MagicBehavior> parseEmail(String recipient) {
        int at = recipient.lastIndexOf('@');
        if (at <= 0 || !recipient.substring(at + 1).equalsIgnoreCase(MAGIC_HOST)) {
            return Optional.empty();
        }
        return parseRule(recipient.substring(0, at));
    }

    private static Optional<MagicBehavior> parseWebhook(String recipient) {
        URI uri;
        try {
            uri = new URI(recipient);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null || !uri.getHost().equalsIgnoreCase(MAGIC_HOST)) {
            return Optional.empty();
        }
        String path = uri.getPath();
        if (path == null || !path.startsWith("/")) {
            return Optional.empty();
        }
        int end = path.indexOf('/', 1);
        return parseRule(end < 0 ? path.substring(1) : path.substring(1, end));
    }

    private static Optional<MagicBehavior> parseSms(String recipient) {
        if (recipient.length() != SMS_LENGTH || !recipient.startsWith(SMS_PREFIX)) {
            return Optional.empty();
        }
        String code = recipient.substring(SMS_PREFIX.length());
        switch (code) {
            case "001":
                return Optional.of(new MagicBehavior(Kind.ALWAYS_TRANSIENT, 0));
            case "002":
                return Optional.of(new MagicBehavior(Kind.ALWAYS_PERMANENT, 0));
            case "004":
                return Optional.of(new MagicBehavior(Kind.FAIL_UNTIL_REPROCESS, FAIL_UNTIL_REPROCESS_ATTEMPTS));
            default:
                Matcher m = SMS_FAIL_N_THEN_OK.matcher(code);
                return m.matches()
                        ? Optional.of(new MagicBehavior(Kind.FAIL_N_THEN_OK, Integer.parseInt(m.group(1))))
                        : Optional.empty();
        }
    }

    /** Regra textual do e-mail e do webhook, sem diferenciar maiúsculas. */
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
     * @param failures quantas tentativas falham antes de suceder ({@code fail-<N>-then-ok}: N, contado
     *                 por {@code retryCount}; {@code fail-until-reprocess}:
     *                 {@link #FAIL_UNTIL_REPROCESS_ATTEMPTS}, contado por {@code attemptNumber}; demais: 0)
     */
    public record MagicBehavior(Kind kind, int failures) {
    }
}
