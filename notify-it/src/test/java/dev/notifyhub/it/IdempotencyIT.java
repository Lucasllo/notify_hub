package dev.notifyhub.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.persistence.Collections;
import dev.notifyhub.common.persistence.NotificationDocument.Fields;
import dev.notifyhub.it.support.EndToEndSupport;
import dev.notifyhub.worker.metrics.DeliveryMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Idempotency-Key no {@code POST /notifications} com MongoDB e RabbitMQ reais (API-03, D-05..D-07):
 * a mesma chave com o mesmo payload devolve a notificação original com {@code Idempotent-Replayed:
 * true}, sem outro documento nem outro envio, e POSTs sem chave continuam criando notificações
 * distintas (o índice único parcial só vale onde a chave existe). A mesma chave com outro payload
 * responde 422 sem vazar a notificação original, e uma corrida de POSTs com a mesma chave nova termina
 * com um documento e um envio (D-05, D-06).
 *
 * <p>Toda espera é com Awaitility; a corrida usa threads virtuais liberadas juntas por um
 * {@code CountDownLatch}.
 */
class IdempotencyIT extends EndToEndSupport {

    private static final String BODY = """
            {"channel":"EMAIL","recipient":"ana@exemplo.com","templateCode":"welcome","variables":{"name":"Ana"}}
            """;
    private static final String BODY_BIA = """
            {"channel":"EMAIL","recipient":"ana@exemplo.com","templateCode":"welcome","variables":{"name":"Bia"}}
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

    @Test
    void sameKeyWithADifferentPayloadIs422() {
        String key = UUID.randomUUID().toString();

        HttpResult first = post("/notifications", BODY, "Idempotency-Key", key);
        assertThat(first.status()).as(first.raw()).isEqualTo(202);
        String id = first.body().path("id").asText();

        HttpResult reused = post("/notifications", BODY_BIA, "Idempotency-Key", key);
        assertThat(reused.status()).as(reused.raw()).isEqualTo(422);
        assertThat(reused.header("Content-Type")).startsWith("application/problem+json");
        assertThat(reused.body().path("type").asText()).isEqualTo("urn:notify-hub:problem:idempotency-key-reused");
        assertThat(reused.raw()).doesNotContain(id).doesNotContain(key);
        assertThat(reused.header(REPLAYED)).isNull();
        assertThat(countWithKey(key)).isEqualTo(1);
        assertThat(mongo().count(new Query(), Collections.NOTIFICATIONS)).isEqualTo(1);
    }

    @Test
    void concurrentPostsWithTheSameNewKeyCreateExactlyOneNotification() throws Exception {
        String key = UUID.randomUUID().toString();
        MeterRegistry registry = worker().getBean(MeterRegistry.class);
        double sendsBefore = successTimer(registry).count();
        int racers = 8;
        CountDownLatch start = new CountDownLatch(1);

        List<HttpResult> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResult>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return post("/notifications", BODY, "Idempotency-Key", key);
                }));
            }
            start.countDown();
            for (Future<HttpResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        }

        assertThat(results).allSatisfy(r -> assertThat(r.status()).as(r.raw()).isEqualTo(202));
        Set<String> ids = results.stream().map(r -> r.body().path("id").asText()).collect(Collectors.toSet());
        assertThat(ids).hasSize(1);
        assertThat(results.stream().filter(r -> r.header(REPLAYED) == null).count())
                .as("exatamente uma resposta é a criação original").isEqualTo(1);
        assertThat(results.stream().filter(r -> "true".equals(r.header(REPLAYED))).count()).isEqualTo(racers - 1);
        assertThat(countWithKey(key)).isEqualTo(1);

        awaitStatus(ids.iterator().next(), NotificationStatus.SENT, SENT_TIMEOUT);
        // Um único envio: o timer do adapter sobe 1 e fica estável
        await("um único envio pelo adapter")
                .atMost(Duration.ofSeconds(5))
                .during(Duration.ofMillis(500))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> successTimer(registry).count() - sendsBefore == 1);
        assertThat(successTimer(registry).count() - sendsBefore).isEqualTo(1);
    }

    @Test
    void invalidKeyIsRejectedWithoutCreatingANotification() {
        HttpResult rejected = post("/notifications", BODY, "Idempotency-Key", "a".repeat(256));

        assertThat(rejected.status()).as(rejected.raw()).isEqualTo(400);
        assertThat(rejected.body().path("type").asText()).isEqualTo("urn:notify-hub:problem:validation-error");
        assertThat(rejected.body().path("errors").path(0).path("field").asText()).isEqualTo("Idempotency-Key");
        assertThat(mongo().count(new Query(), Collections.NOTIFICATIONS)).isZero();
    }

    private static Timer successTimer(MeterRegistry registry) {
        return registry.get(DeliveryMetrics.DURATION).tags("channel", "email", "outcome", "success").timer();
    }

    private static long countWithKey(String key) {
        return mongo().count(new Query(Criteria.where(Fields.IDEMPOTENCY_KEY).is(key)), Collections.NOTIFICATIONS);
    }
}
