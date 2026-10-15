package dev.notifyhub.worker.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.samskivert.mustache.Mustache;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.persistence.RenderedContent;
import dev.notifyhub.worker.failure.ErrorCode;
import dev.notifyhub.worker.failure.PermanentDeliveryException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Renderiza a variante de um template para um canal com JMustache, com escape por canal (TPL-03,
 * D-16).
 *
 * <p>Três compiladores imutáveis:
 * <ul>
 *   <li>{@link #HTML} (escape HTML padrão do JMustache: {@code & ' " < > ` =}) só para o corpo do
 *       EMAIL;</li>
 *   <li>{@link #PLAIN} ({@code escapeHTML(false)}) para o assunto do EMAIL e o SMS;</li>
 *   <li>{@link #JSON} ({@link JsonValueEscaper}) para o corpo do WEBHOOK: cada valor sai escapado
 *       como string JSON. Nunca encadeie {@code escapeHTML(...)} depois de {@code withEscaper}: o
 *       JMustache sobrescreve o escaper sem aviso.</li>
 * </ul>
 *
 * <p>Depois de renderizar, o corpo do WEBHOOK passa por um parse estrito ({@link #STRICT_JSON}, com
 * {@code FAIL_ON_TRAILING_TOKENS}) e precisa ser um objeto JSON. Se não for, a renderização falha com
 * {@link ErrorCode#INVALID_WEBHOOK_PAYLOAD} e nenhum corpo corrompido sai.
 *
 * <p>Renderização estrita (TPL-02): antes de compilar, cada nome de {@code requiredVariables} precisa
 * estar presente e não nulo nas variáveis, senão a falha é permanente com
 * {@link ErrorCode#MISSING_VARIABLE} (a mensagem cita só o nome). Nenhum valor substituto é
 * configurado no JMustache: variável usada pelo template e fora de {@code requiredVariables} ainda
 * lança {@code MustacheException.Context}, que o {@code FailureClassifier} trata como
 * PERMANENT/MISSING_VARIABLE, e erro de sintaxe lança {@code MustacheException} (TEMPLATE_INVALID).
 * A renderização nunca sai em branco.
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
    private static final Mustache.Compiler JSON =
            Mustache.compiler().withEscaper(new JsonValueEscaper()).strictSections(true);

    /** Parse estrito: o {@code readTree} padrão aceita lixo depois do objeto. */
    private static final ObjectReader STRICT_JSON =
            new ObjectMapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Renderiza a variante do canal.
     *
     * @throws TemplateNotFoundException se o template não tiver variante para o canal
     * @throws PermanentDeliveryException com {@link ErrorCode#MISSING_VARIABLE} se faltar (ou for
     *         nula) uma variável de {@code requiredVariables}, ou com
     *         {@link ErrorCode#INVALID_WEBHOOK_PAYLOAD} se o corpo do webhook não for um objeto JSON
     *         válido
     * @throws com.samskivert.mustache.MustacheException se o template usar uma variável ausente que
     *         não está em {@code requiredVariables}, ou se não compilar
     */
    public RenderedContent render(TemplateDocument template, Channel channel,
                                  Map<String, Object> variables, String notificationId) {
        TemplateVariant variant = template.variants() == null ? null : template.variants().get(channel);
        if (variant == null) {
            throw TemplateNotFoundException.missingVariant(template.id(), channel);
        }
        requireVariables(template.requiredVariables(), variables);

        Map<String, Object> context = new HashMap<>(variables == null ? Map.of() : variables);
        context.put(NOTIFICATION_ID_VARIABLE, notificationId);

        return switch (channel) {
            case EMAIL -> new RenderedContent(
                    variant.subject() == null ? null : execute(PLAIN, variant.subject(), context),
                    execute(HTML, variant.body(), context));
            case SMS -> new RenderedContent(null, execute(PLAIN, variant.body(), context));
            case WEBHOOK -> new RenderedContent(null,
                    requireJsonObject(template.id(), execute(JSON, variant.body(), context)));
        };
    }

    /**
     * Cada variável obrigatória precisa estar presente e não nula; string vazia é valor válido. A
     * mensagem cita só o nome da variável, nunca valores. {@code _notificationId} é injetado pelo
     * worker e nunca está em {@code requiredVariables}.
     */
    private static void requireVariables(List<String> required, Map<String, Object> variables) {
        if (required == null) {
            return;
        }
        for (String name : required) {
            if (variables == null || variables.get(name) == null) {
                throw new PermanentDeliveryException(ErrorCode.MISSING_VARIABLE,
                        "variável obrigatória ausente: " + name);
            }
        }
    }

    private static String execute(Mustache.Compiler compiler, String text, Map<String, Object> context) {
        return compiler.compile(text).execute(context);
    }

    /**
     * Garante que o corpo do webhook é um único objeto JSON. A causa do Jackson não é anexada: a
     * mensagem dela cita trechos do corpo, que podem conter dados do cliente.
     */
    private static String requireJsonObject(String templateId, String body) {
        JsonNode node;
        try {
            node = STRICT_JSON.readTree(body);
        } catch (JsonProcessingException e) {
            throw invalidWebhookPayload(templateId);
        }
        if (node == null || !node.isObject()) {
            throw invalidWebhookPayload(templateId);
        }
        return body;
    }

    private static PermanentDeliveryException invalidWebhookPayload(String templateId) {
        return new PermanentDeliveryException(ErrorCode.INVALID_WEBHOOK_PAYLOAD,
                "corpo do webhook do template " + templateId + " não é um objeto JSON válido");
    }
}
