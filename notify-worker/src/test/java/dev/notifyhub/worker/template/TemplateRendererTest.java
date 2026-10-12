package dev.notifyhub.worker.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samskivert.mustache.MustacheException;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.persistence.RenderedContent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Escape por canal e renderização estrita. */
class TemplateRendererTest {

    private static final TemplateDocument WELCOME = new TemplateDocument("welcome", 1, List.of("name"),
            Map.of(
                    Channel.EMAIL, new TemplateVariant(
                            "Boas-vindas ao Notify Hub, {{name}}!",
                            "<p>Olá, {{name}}!</p>"),
                    Channel.SMS, new TemplateVariant(null, "Olá, {{name}}!"),
                    Channel.WEBHOOK, new TemplateVariant(null,
                            "{\"event\":\"user.welcome\",\"notificationId\":\"{{_notificationId}}\","
                                    + "\"data\":{\"name\":\"{{name}}\"}}")));

    private final TemplateRenderer renderer = new TemplateRenderer();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void emailBodyEscapesHtml() {
        RenderedContent email = renderer.render(WELCOME, Channel.EMAIL, Map.of("name", "<b>Ana</b>"), "n-1");

        assertThat(email.body()).contains("&lt;b&gt;Ana&lt;/b&gt;").doesNotContain("<b>");
    }

    @Test
    void emailSubjectIsPlainText() {
        RenderedContent email = renderer.render(WELCOME, Channel.EMAIL, Map.of("name", "Ana & Bia"), "n-1");

        assertThat(email.subject()).contains("Ana & Bia").doesNotContain("&amp;");
    }

    @Test
    void smsIsPlainText() {
        RenderedContent sms = renderer.render(WELCOME, Channel.SMS, Map.of("name", "Ana & Bia"), "n-1");

        assertThat(sms.subject()).isNull();
        assertThat(sms.body()).contains("Ana & Bia").doesNotContain("&amp;");
    }

    @Test
    void webhookIsValidJsonWithoutHtmlEntitiesAndCarriesNotificationId() throws Exception {
        RenderedContent webhook = renderer.render(WELCOME, Channel.WEBHOOK, Map.of("name", "Ana"), "n-7");

        assertThat(webhook.body()).doesNotContain("&quot;").contains("n-7");
        JsonNode envelope = json.readTree(webhook.body());
        assertThat(envelope.path("notificationId").asText()).isEqualTo("n-7");
        assertThat(envelope.path("data").path("name").asText()).isEqualTo("Ana");
    }

    @Test
    void missingVariableThrowsInsteadOfRenderingBlank() {
        assertThatThrownBy(() -> renderer.render(WELCOME, Channel.SMS, Map.of(), "n-1"))
                .isInstanceOf(MustacheException.class);
    }

    @Test
    void nullVariableThrowsInsteadOfRenderingBlank() {
        Map<String, Object> variables = new java.util.HashMap<>();
        variables.put("name", null);

        assertThatThrownBy(() -> renderer.render(WELCOME, Channel.EMAIL, variables, "n-1"))
                .isInstanceOf(MustacheException.class);
    }

    @Test
    void missingChannelVariantIsTemplateNotFound() {
        TemplateDocument emailOnly = new TemplateDocument("so-email", 1, List.of("name"),
                Map.of(Channel.EMAIL, new TemplateVariant("Oi", "<p>{{name}}</p>")));

        assertThatThrownBy(() -> renderer.render(emailOnly, Channel.SMS, Map.of("name", "Ana"), "n-1"))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasMessageContaining("so-email")
                .hasMessageContaining("SMS");
    }

    @Test
    void doesNotMutateCallerVariables() {
        Map<String, Object> variables = new java.util.HashMap<>(Map.of("name", "Ana"));

        renderer.render(WELCOME, Channel.WEBHOOK, variables, "n-1");

        assertThat(variables).containsOnlyKeys("name");
    }
}
