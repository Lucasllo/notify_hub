package dev.notifyhub.worker.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.persistence.RenderedContent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Caminho inteiro do template sem banco: arquivo de seed no classpath → seeder → catálogo →
 * renderizador → texto pt-BR por canal.
 */
class SeedTemplatesTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final ObjectMapper json = new ObjectMapper();
    private final TemplateSeeder seeder = new TemplateSeeder(mongo, json);
    private final TemplateCatalog catalog = new TemplateCatalog(mongo);
    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    void seedInsertsWelcomeTemplateWhenCollectionIsEmpty() {
        TemplateDocument welcome = seededWelcome();

        assertThat(welcome.version()).isEqualTo(1);
        assertThat(welcome.requiredVariables()).containsExactly("name");
        assertThat(welcome.variants()).containsOnlyKeys(Channel.EMAIL, Channel.SMS, Channel.WEBHOOK);
    }

    @Test
    void catalogAndRendererTurnWelcomeIntoPortugueseTextPerChannel() {
        TemplateDocument welcome = seededWelcome();
        when(mongo.findById("welcome", TemplateDocument.class)).thenReturn(welcome);

        TemplateDocument found = catalog.require("welcome");
        RenderedContent email = renderer.render(found, Channel.EMAIL, Map.of("name", "Ana"), "n-1");
        RenderedContent sms = renderer.render(found, Channel.SMS, Map.of("name", "Ana"), "n-1");

        assertThat(email.subject()).contains("Ana");
        assertThat(email.body()).contains("Olá, Ana");
        assertThat(sms.subject()).isNull();
        assertThat(sms.body()).contains("Ana");
    }

    @Test
    void welcomeWebhookRendersValidJsonEnvelope() throws Exception {
        TemplateDocument welcome = seededWelcome();

        RenderedContent webhook = renderer.render(welcome, Channel.WEBHOOK, Map.of("name", "Ana"), "n-1");
        JsonNode envelope = json.readTree(webhook.body());

        assertThat(webhook.subject()).isNull();
        assertThat(webhook.body()).doesNotContain("&quot;");
        assertThat(envelope.path("event").asText()).isEqualTo("user.welcome");
        assertThat(envelope.path("notificationId").asText()).isEqualTo("n-1");
        assertThat(envelope.path("data").path("name").asText()).isEqualTo("Ana");
    }

    @Test
    void catalogRejectsUnknownTemplate() {
        assertThatThrownBy(() -> catalog.require("nao-existe"))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasMessageContaining("nao-existe");
    }

    /** Roda o seed contra um banco vazio e devolve o documento {@code welcome} inserido. */
    private TemplateDocument seededWelcome() {
        when(mongo.findById(any(), eq(TemplateDocument.class))).thenReturn(null);

        seeder.seed();

        ArgumentCaptor<TemplateDocument> inserted = ArgumentCaptor.forClass(TemplateDocument.class);
        verify(mongo, atLeastOnce()).insert(inserted.capture());
        List<TemplateDocument> documents = inserted.getAllValues();
        return documents.stream()
                .filter(doc -> doc.id().equals("welcome"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("seed não inseriu o template welcome"));
    }
}
