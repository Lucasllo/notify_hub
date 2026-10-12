package dev.notifyhub.worker.template;

import com.samskivert.mustache.Mustache;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.persistence.RenderedContent;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Renderiza a variante de um template para um canal com JMustache.
 *
 * <p>Dois compiladores imutáveis: {@link #HTML} (escape HTML padrão) só para o corpo do EMAIL, e
 * {@link #PLAIN} ({@code escapeHTML(false)}) para o assunto do EMAIL, o SMS e o WEBHOOK — o WEBHOOK
 * não pode sair com {@code &quot;} (o escape JSON dos valores é da Fase 2, TPL-03).
 *
 * <p>Nenhum valor substituto é configurado para variável ausente ou nula: o padrão do JMustache
 * lança exceção, e a renderização nunca sai em branco.
 *
 * <p>Só se compilam templates do seed. Texto enviado pelo cliente entra apenas como dado do
 * contexto, nunca como template (T-01-10).
 */
@Component
public class TemplateRenderer {

    /** Variável reservada injetada pelo worker (D-09); o cliente não a envia. */
    public static final String NOTIFICATION_ID_VARIABLE = "_notificationId";

    private static final Mustache.Compiler HTML = Mustache.compiler();
    private static final Mustache.Compiler PLAIN = Mustache.compiler().escapeHTML(false);

    /**
     * Renderiza a variante do canal.
     *
     * @throws TemplateNotFoundException se o template não tiver variante para o canal
     * @throws com.samskivert.mustache.MustacheException se faltar uma variável no contexto
     */
    public RenderedContent render(TemplateDocument template, Channel channel,
                                  Map<String, Object> variables, String notificationId) {
        TemplateVariant variant = template.variants() == null ? null : template.variants().get(channel);
        if (variant == null) {
            throw TemplateNotFoundException.missingVariant(template.id(), channel);
        }

        Map<String, Object> context = new HashMap<>(variables == null ? Map.of() : variables);
        context.put(NOTIFICATION_ID_VARIABLE, notificationId);

        return switch (channel) {
            case EMAIL -> new RenderedContent(
                    variant.subject() == null ? null : execute(PLAIN, variant.subject(), context),
                    execute(HTML, variant.body(), context));
            case SMS, WEBHOOK -> new RenderedContent(null, execute(PLAIN, variant.body(), context));
        };
    }

    private static String execute(Mustache.Compiler compiler, String text, Map<String, Object> context) {
        return compiler.compile(text).execute(context);
    }
}
