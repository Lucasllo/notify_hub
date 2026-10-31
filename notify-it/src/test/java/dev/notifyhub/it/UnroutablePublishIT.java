package dev.notifyhub.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.messaging.NotifyTopology;
import dev.notifyhub.common.persistence.Collections;
import dev.notifyhub.common.persistence.NotificationDocument.Fields;
import dev.notifyhub.it.support.EndToEndSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Publish não roteável contra o RabbitMQ real (TST-03) e a saída de PUBLISH_FAILED pelo operador
 * (ADM-03): sem o binding {@code notify.exchange → notify.email}, o {@code POST /notifications} vira
 * 503 com {@code Retry-After} e o documento fica PUBLISH_FAILED com {@code lastError} fixo, sem o reply
 * text do broker (T-02-01, T-03-21). Com o binding de volta, {@code POST
 * /admin/notifications/republish-failed} republica a notificação, que segue até SENT.
 *
 * <p>O replay com a mesma {@code Idempotency-Key} de uma notificação PUBLISH_FAILED republica pelo
 * mesmo serviço (D-08): 202 PENDING com o broker roteando, 503 com {@code Retry-After} sem rota. O lote
 * do admin para na primeira falha com 503 parcial e deixa o que faltou em PUBLISH_FAILED.
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

    @Test
    void replayOfAPublishFailedNotificationRepublishes() {
        String key = UUID.randomUUID().toString();
        String id;
        try {
            removeEmailBinding();
            HttpResult created = post("/notifications", BODY, "Idempotency-Key", key);
            assertThat(created.status()).as(created.raw()).isEqualTo(503);
            id = created.body().path("notificationId").asText();
            assertThat(get("/notifications/" + id).body().path("status").asText()).isEqualTo("PUBLISH_FAILED");
        } finally {
            restoreEmailBinding();
        }

        HttpResult replay = post("/notifications", BODY, "Idempotency-Key", key);
        assertThat(replay.status()).as(replay.raw()).isEqualTo(202);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body().path("id").asText()).isEqualTo(id);
        assertThat(replay.body().path("status").asText()).isEqualTo("PENDING");

        awaitStatus(id, NotificationStatus.SENT, SENT_TIMEOUT);
        assertThat(countWithKey(key)).isEqualTo(1);
    }

    @Test
    void replayThatFailsAgainAnswers503WithRetryAfter() {
        String key = UUID.randomUUID().toString();
        try {
            removeEmailBinding();
            HttpResult first = post("/notifications", BODY, "Idempotency-Key", key);
            assertThat(first.status()).as(first.raw()).isEqualTo(503);
            assertThat(first.header("Retry-After")).isEqualTo("5");
            String id = first.body().path("notificationId").asText();

            HttpResult second = post("/notifications", BODY, "Idempotency-Key", key);
            assertThat(second.status()).as(second.raw()).isEqualTo(503);
            assertThat(second.header("Retry-After")).isEqualTo("5");
            assertThat(second.body().path("type").asText()).isEqualTo("urn:notify-hub:problem:publish-failed");
            assertThat(second.body().path("notificationId").asText()).isEqualTo(id);
            assertThat(second.raw()).doesNotContain("NO_ROUTE");

            HttpResult view = get("/notifications/" + id);
            assertThat(view.body().path("status").asText()).isEqualTo("PUBLISH_FAILED");
            assertThat(view.body().path("lastError").path("code").asText()).isEqualTo("PUBLISH_RETURNED");
            assertThat(countWithKey(key)).isEqualTo(1);
        } finally {
            restoreEmailBinding();
        }
    }

    @Test
    void republishBatchStopsAtTheFirstFailureWithPartialCounts() {
        String first;
        String second;
        try {
            removeEmailBinding();
            first = createPublishFailed();
            second = createPublishFailed();

            HttpResult interrupted = adminPost(REPUBLISH_PATH + "?max=10");
            assertThat(interrupted.status()).as(interrupted.raw()).isEqualTo(503);
            assertThat(interrupted.body().path("type").asText())
                    .isEqualTo("urn:notify-hub:problem:republish-interrupted");
            assertThat(interrupted.body().path("requested").asInt()).isEqualTo(10);
            assertThat(interrupted.body().path("republished").asInt()).isZero();
            assertThat(interrupted.body().path("reason").asText()).isEqualTo("PUBLISH_RETURNED");
            assertThat(interrupted.raw()).doesNotContain("NO_ROUTE");

            assertThat(get("/notifications/" + first).body().path("status").asText()).isEqualTo("PUBLISH_FAILED");
            assertThat(get("/notifications/" + second).body().path("status").asText()).isEqualTo("PUBLISH_FAILED");
        } finally {
            restoreEmailBinding();
        }

        HttpResult republished = adminPost(REPUBLISH_PATH + "?max=10");
        assertThat(republished.status()).as(republished.raw()).isEqualTo(200);
        assertThat(republished.body().path("requested").asInt()).isEqualTo(10);
        assertThat(republished.body().path("republished").asInt()).isEqualTo(2);

        awaitStatus(first, NotificationStatus.SENT, SENT_TIMEOUT);
        awaitStatus(second, NotificationStatus.SENT, SENT_TIMEOUT);
    }

    /** POST sem rota: 503 e o id da notificação PUBLISH_FAILED. */
    private static String createPublishFailed() {
        HttpResult created = post("/notifications", BODY);
        assertThat(created.status()).as(created.raw()).isEqualTo(503);
        return created.body().path("notificationId").asText();
    }

    private static long countWithKey(String key) {
        return mongo().count(new Query(Criteria.where(Fields.IDEMPOTENCY_KEY).is(key)), Collections.NOTIFICATIONS);
    }
}
