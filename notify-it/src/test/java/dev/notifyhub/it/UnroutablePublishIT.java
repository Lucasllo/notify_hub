package dev.notifyhub.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.messaging.NotifyTopology;
import dev.notifyhub.it.support.EndToEndSupport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;

/**
 * Publish não roteável contra o RabbitMQ real (TST-03) e a saída de PUBLISH_FAILED pelo operador
 * (ADM-03): sem o binding {@code notify.exchange → notify.email}, o {@code POST /notifications} vira
 * 503 com {@code Retry-After} e o documento fica PUBLISH_FAILED com {@code lastError} fixo, sem o reply
 * text do broker (T-02-01, T-03-21). Com o binding de volta, {@code POST
 * /admin/notifications/republish-failed} republica a notificação, que segue até SENT.
 *
 * <p>Armadilha 14: o binding removido é sempre restaurado num {@code finally}, porque um binding
 * perdido deixaria todos os ITs de e-mail seguintes em 503.
 */
class UnroutablePublishIT extends EndToEndSupport {

    private static final String BODY = """
            {"channel":"EMAIL","recipient":"ana@exemplo.com","templateCode":"welcome","variables":{"name":"Ana"}}
            """;
    private static final String REPUBLISH_PATH = "/admin/notifications/republish-failed";
    private static final Duration SENT_TIMEOUT = Duration.ofSeconds(15);

    private static final Binding EMAIL_BINDING = new Binding(Channel.EMAIL.queue(), Binding.DestinationType.QUEUE,
            NotifyTopology.MAIN_EXCHANGE, Channel.EMAIL.routingKey(), null);

    private static AmqpAdmin admin() {
        return api().getBean(AmqpAdmin.class);
    }

    private static void removeEmailBinding() {
        admin().removeBinding(EMAIL_BINDING);
    }

    private static void restoreEmailBinding() {
        admin().declareBinding(EMAIL_BINDING);
    }

    @Test
    void unroutablePublishBecomesPublishFailedAndIsRepublishedByTheOperator() {
        String id;
        try {
            removeEmailBinding();

            HttpResult created = post("/notifications", BODY);
            assertThat(created.status()).as(created.raw()).isEqualTo(503);
            assertThat(created.header("Retry-After")).isEqualTo("5");
            assertThat(created.body().path("type").asText()).isEqualTo("urn:notify-hub:problem:publish-failed");
            assertThat(created.raw()).doesNotContain("NO_ROUTE");
            id = created.body().path("notificationId").asText();
            assertThat(id).isNotBlank();

            HttpResult view = get("/notifications/" + id);
            assertThat(view.status()).isEqualTo(200);
            assertThat(view.body().path("status").asText()).isEqualTo("PUBLISH_FAILED");
            JsonNode lastError = view.body().path("lastError");
            assertThat(lastError.path("code").asText()).isEqualTo("PUBLISH_RETURNED");
            assertThat(lastError.path("message").asText()).contains("PUBLISH_RETURNED").doesNotContain("NO_ROUTE");
            assertThat(view.raw()).doesNotContain("NO_ROUTE");

            // T-03-23: sem chave, 401 e nada muda
            HttpResult unauthorized = post(REPUBLISH_PATH, "");
            assertThat(unauthorized.status()).as(unauthorized.raw()).isEqualTo(401);
            assertThat(get("/notifications/" + id).body().path("status").asText()).isEqualTo("PUBLISH_FAILED");
        } finally {
            restoreEmailBinding();
        }

        HttpResult republished = adminPost(REPUBLISH_PATH);
        assertThat(republished.status()).as(republished.raw()).isEqualTo(200);
        assertThat(republished.body().path("requested").asInt()).isEqualTo(100);
        assertThat(republished.body().path("republished").asInt()).isEqualTo(1);

        awaitStatus(id, NotificationStatus.SENT, SENT_TIMEOUT);
    }
}
