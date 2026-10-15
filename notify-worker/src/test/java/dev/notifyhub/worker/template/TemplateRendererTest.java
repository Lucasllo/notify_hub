package dev.notifyhub.worker.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samskivert.mustache.MustacheException;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.FailureClass;
import dev.notifyhub.common.persistence.RenderedContent;
import dev.notifyhub.worker.failure.ErrorCode;
import dev.notifyhub.worker.failure.FailureClassifier;
import dev.notifyhub.worker.failure.PermanentDeliveryException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

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
    private final FailureClassifier classifier = new FailureClassifier();
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
    void missingRequiredVariableIsPermanentMissingVariable() {
        assertThatThrownBy(() -> renderer.render(WELCOME, Channel.SMS, Map.of(), "n-1"))
                .isInstanceOf(PermanentDeliveryException.class)
                .satisfies(e -> assertThat(((PermanentDeliveryException) e).errorCode())
                        .isEqualTo(ErrorCode.MISSING_VARIABLE))
                .hasMessageContaining("name");
    }

    @Test
    void nullRequiredVariableIsPermanentMissingVariable() {
        Map<String, Object> variables = new java.util.HashMap<>();
        variables.put("name", null);

        assertThatThrownBy(() -> renderer.render(WELCOME, Channel.EMAIL, variables, "n-1"))
                .isInstanceOf(PermanentDeliveryException.class)
                .satisfies(e -> assertThat(((PermanentDeliveryException) e).errorCode())
                        .isEqualTo(ErrorCode.MISSING_VARIABLE))
                .hasMessageContaining("name");
    }

    @Test
    void emptyRequiredVariableIsAValidValue() {
        RenderedContent sms = renderer.render(WELCOME, Channel.SMS, Map.of("name", ""), "n-1");

        assertThat(sms.body()).isEqualTo("Olá, !");
    }

    @Test
    void requiredVariableIsEnforcedEvenWhenTheTemplateDoesNotUseIt() {
        TemplateDocument withCpf = new TemplateDocument("com-cpf", 1, List.of("name", "cpf"),
                Map.of(Channel.SMS, new TemplateVariant(null, "Olá, {{name}}!")));

        assertThatThrownBy(() -> renderer.render(withCpf, Channel.SMS,
                Map.of("name", "valor-secreto-do-nome"), "n-1"))
                .isInstanceOf(PermanentDeliveryException.class)
                .satisfies(e -> assertThat(((PermanentDeliveryException) e).errorCode())
                        .isEqualTo(ErrorCode.MISSING_VARIABLE))
                .hasMessageContaining("cpf")
                .hasMessageNotContaining("valor-secreto-do-nome");
    }

    @Test
    void variableUsedButNotRequiredStillFailsAsPermanentMissingVariable() {
        TemplateDocument loose = new TemplateDocument("solto", 1, List.of(),
                Map.of(Channel.SMS, new TemplateVariant(null, "Oi, {{apelido}}")));

        RuntimeException e = catchRuntime(() -> renderer.render(loose, Channel.SMS, Map.of(), "n-1"));

        assertThat(e).isInstanceOf(MustacheException.Context.class);
        assertThat(classifier.classify(e).failureClass()).isEqualTo(FailureClass.PERMANENT);
        assertThat(classifier.classify(e).errorCode()).isEqualTo(ErrorCode.MISSING_VARIABLE);
    }

    @Test
    void brokenTemplateSyntaxIsPermanentTemplateInvalid() {
        TemplateDocument broken = new TemplateDocument("quebrado", 1, List.of(),
                Map.of(Channel.SMS, new TemplateVariant(null, "{{#a}} sem fechar")));

        RuntimeException e = catchRuntime(() -> renderer.render(broken, Channel.SMS, Map.of("a", true), "n-1"));

        assertThat(classifier.classify(e).failureClass()).isEqualTo(FailureClass.PERMANENT);
        assertThat(classifier.classify(e).errorCode()).isEqualTo(ErrorCode.TEMPLATE_INVALID);
    }

    @Test
    void missingVariantIsClassifiedAsPermanentTemplateNotFound() {
        TemplateDocument emailOnly = new TemplateDocument("so-email", 1, List.of("name"),
                Map.of(Channel.EMAIL, new TemplateVariant("Oi", "<p>{{name}}</p>")));

        RuntimeException e = catchRuntime(() -> renderer.render(emailOnly, Channel.WEBHOOK, Map.of("name", "Ana"), "n-1"));

        assertThat(classifier.classify(e).failureClass()).isEqualTo(FailureClass.PERMANENT);
        assertThat(classifier.classify(e).errorCode()).isEqualTo(ErrorCode.TEMPLATE_NOT_FOUND);
    }

    @Test
    void emailBodyEscapesEveryHtmlSensitiveCharacter() {
        RenderedContent email = renderer.render(WELCOME, Channel.EMAIL,
                Map.of("name", "<a href='x'>&\"=`"), "n-1");

        assertThat(email.body())
                .contains("&lt;a href&#x3D;&#39;x&#39;&gt;&amp;&quot;&#x3D;&#x60;")
                .doesNotContain("<a href");
    }

    @Test
    void emailSubjectAndSmsKeepHtmlCharactersLiteral() {
        Map<String, Object> variables = Map.of("name", "<b>&\"");

        RenderedContent email = renderer.render(WELCOME, Channel.EMAIL, variables, "n-1");
        RenderedContent sms = renderer.render(WELCOME, Channel.SMS, variables, "n-1");

        assertThat(email.subject()).contains("<b>&\"");
        assertThat(sms.body()).contains("<b>&\"");
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

    // --- Fase 2: escape JSON no webhook e validação estrita do corpo (TPL-03, D-16) ---

    /** Valor hostil: aspas, barra invertida, quebra de linha, tab, U+0001 e tag de script. */
    private static final String HOSTILE_NAME = "Ana \"Bia\" \\ back\nline\ttab\u0001 </script>";

    @Test
    void seedPasswordResetWebhookStaysValidJsonWithQuotesBackslashAndControls() throws Exception {
        TemplateDocument passwordReset = seedTemplate("password-reset");

        RenderedContent webhook = renderer.render(passwordReset, Channel.WEBHOOK,
                Map.of("name", HOSTILE_NAME, "resetCode", "789012"), "n-99");

        assertThatCode(() -> json.readTree(webhook.body()))
                .as("corpo do webhook precisa ser JSON válido")
                .doesNotThrowAnyException();
        JsonNode envelope = json.readTree(webhook.body());
        assertThat(envelope.isObject()).isTrue();
        assertThat(envelope.path("data").path("name").asText()).isEqualTo(HOSTILE_NAME);
        assertThat(envelope.path("data").path("resetCode").asText()).isEqualTo("789012");
        assertThat(envelope.path("notificationId").asText()).isEqualTo("n-99");
    }

    @Test
    void webhookBodyNeverCarriesHtmlEntities() {
        TemplateDocument passwordReset = seedTemplate("password-reset");

        RenderedContent webhook = renderer.render(passwordReset, Channel.WEBHOOK,
                Map.of("name", HOSTILE_NAME, "resetCode", "<1&2>"), "n-1");

        assertThat(webhook.body()).doesNotContain("&quot;").doesNotContain("&lt;").doesNotContain("&amp;");
    }

    @Test
    void webhookWithTrailingGarbageIsInvalidPayload() {
        assertInvalidWebhookPayload("{\"a\":\"{{x}}\"} lixo");
    }

    @Test
    void webhookThatRendersAnArrayIsInvalidPayload() {
        assertInvalidWebhookPayload("[1,2]");
    }

    @Test
    void webhookThatRendersOnlyWhitespaceIsInvalidPayload() {
        assertInvalidWebhookPayload("   ");
    }

    private void assertInvalidWebhookPayload(String webhookBody) {
        TemplateDocument broken = new TemplateDocument("webhook-quebrado", 1, List.of(),
                Map.of(Channel.WEBHOOK, new TemplateVariant(null, webhookBody)));
        Map<String, Object> variables = Map.of("x", "segredo-do-cliente");

        assertThatThrownBy(() -> renderer.render(broken, Channel.WEBHOOK, variables, "n-1"))
                .isInstanceOf(PermanentDeliveryException.class)
                .satisfies(e -> assertThat(((PermanentDeliveryException) e).errorCode())
                        .isEqualTo(ErrorCode.INVALID_WEBHOOK_PAYLOAD))
                .hasMessageContaining("webhook-quebrado")
                .hasMessageNotContaining("segredo-do-cliente")
                .hasNoCause();
    }

    /** Executa e devolve a exceção real lançada pelo renderer, para classificá-la. */
    private static RuntimeException catchRuntime(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).as("o renderer devia lançar").isInstanceOf(RuntimeException.class);
        return (RuntimeException) thrown;
    }

    /** Lê um template do seed real do classpath, como o {@code SeedTemplatesTest}. */
    private TemplateDocument seedTemplate(String id) {
        return new TemplateSeeder(mock(MongoTemplate.class), json).loadSeed().stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("seed sem o template " + id));
    }
}
