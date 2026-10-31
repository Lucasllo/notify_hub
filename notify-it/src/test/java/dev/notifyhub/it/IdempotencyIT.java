package dev.notifyhub.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.persistence.Collections;
import dev.notifyhub.common.persistence.NotificationDocument.Fields;
import dev.notifyhub.it.support.EndToEndSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Idempotency-Key no {@code POST /notifications} com MongoDB e RabbitMQ reais (API-03, D-05..D-07):
 * a mesma chave com o mesmo payload devolve a notificação original com {@code Idempotent-Replayed:
 * true}, sem outro documento nem outro envio, e POSTs sem chave continuam criando notificações
 * distintas (o índice único parcial só vale onde a chave existe).
 */
class IdempotencyIT extends EndToEndSupport {

    private static final String BODY = """
            {"channel":"EMAIL","recipient":"ana@exemplo.com","templateCode":"welcome","variables":{"name":"Ana"}}
            """;
    private static final String REPLAYED = "Idempotent-Replayed";
    private static final Duration SENT_TIMEOUT = Duration.ofSeconds(15);

    @Test
    void samePayloadWithTheSameKeyReplaysTheOriginal() {
        String key = UUID.randomUUID().toString();

        HttpResult first = post("/notifications", BODY, "Idempotency-Key", key);
        assertThat(first.status()).as(first.raw()).isEqualTo(202);
        assertThat(first.header(REPLAYED)).as("primeiro POST não é replay").isNull();
        String id = first.body().path("id").asText();
        assertThat(first.header("Location")).isEqualTo("/notifications/" + id);

        HttpResult second = post("/notifications", BODY, "Idempotency-Key", key);
        assertThat(second.status()).as(second.raw()).isEqualTo(202);
        assertThat(second.body().path("id").asText()).isEqualTo(id);
        assertThat(second.header("Location")).isEqualTo(first.header("Location"));
        assertThat(second.header(REPLAYED)).isEqualTo("true");
        assertThat(countWithKey(key)).isEqualTo(1);

        awaitStatus(id, NotificationStatus.SENT, SENT_TIMEOUT);

        HttpResult third = post("/notifications", BODY, "Idempotency-Key", key);
        assertThat(third.status()).as(third.raw()).isEqualTo(202);
        assertThat(third.body().path("id").asText()).isEqualTo(id);
        assertThat(third.body().path("status").asText()).isEqualTo("SENT");
        assertThat(third.header(REPLAYED)).isEqualTo("true");
        assertThat(countWithKey(key)).isEqualTo(1);
    }

    @Test
    void postsWithoutKeyStillCreateDistinctNotifications() {
        HttpResult first = post("/notifications", BODY);
        HttpResult second = post("/notifications", BODY);

        assertThat(first.status()).as(first.raw()).isEqualTo(202);
        assertThat(second.status()).as(second.raw()).isEqualTo(202);
        String firstId = first.body().path("id").asText();
        String secondId = second.body().path("id").asText();
        assertThat(firstId).isNotEqualTo(secondId);
        assertThat(first.header(REPLAYED)).isNull();
        assertThat(second.header(REPLAYED)).isNull();

        awaitStatus(firstId, NotificationStatus.SENT, SENT_TIMEOUT);
        awaitStatus(secondId, NotificationStatus.SENT, SENT_TIMEOUT);
    }

    private static long countWithKey(String key) {
        return mongo().count(new Query(Criteria.where(Fields.IDEMPOTENCY_KEY).is(key)), Collections.NOTIFICATIONS);
    }
}
