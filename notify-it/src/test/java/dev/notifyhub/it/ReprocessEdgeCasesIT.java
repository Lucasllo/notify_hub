package dev.notifyhub.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.messaging.NotifyHeaders;
import dev.notifyhub.common.messaging.NotifyTopology;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.common.persistence.NotificationDocument.Fields;
import dev.notifyhub.it.support.EndToEndSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Casos de borda do reprocessamento da DLQ com RabbitMQ e MongoDB reais (ADM-01, CR-01 da Fase 2,
 * D-01, D-02, D-04 e o edge de concorrência do ADM-04).
 *
 * <p><b>Montagem.</b> Os documentos são inseridos direto no Mongo e as mensagens postas na DLQ pelo
 * {@code RabbitTemplate} da API em {@code notify.dlx}/{@code email.dlq}, com {@code messageId} = id e
 * {@code x-failure-class: TRANSIENT}, como a cópia que o worker faz. Assim cada cenário monta o estado
 * exato que quer provar (um documento PENDING, RETRYING ou PROCESSING com a mensagem na DLQ é o que o
 * CR-01 descreve: falha do Mongo no claim ou no update de resultado), sem depender de falhas
 * injetadas no worker.
 *
 * <p><b>CR-01.</b> Antes do plano 03-03, só FAILED e DEAD_LETTERED iam a PENDING, e um documento não
 * terminal na cabeça da DLQ travava o canal. Agora PENDING, RETRYING e PROCESSING com lease vencido ou
 * ausente são reprocessados; PROCESSING com lease vigente (um worker pode estar com ele) é preservado
 * no fim da DLQ ({@code skipped}) e retomado quando o lease vence.
 *
 * <p>Toda espera é com Awaitility; {@code unackedCount} vem da API de gerenciamento, que agrega a cada
 * ~5s, então as asserções sobre ele usam {@code atMost} de 15s.
 */
class ReprocessEdgeCasesIT extends EndToEndSupport {

    private static final String DLQ = "notify.email.dlq";
    private static final String REPROCESS_10 = "/admin/dlq/email/reprocess?max=10";

    @Test
    void nonTerminalDocumentsWhoseMessageIsInTheDlqAreReprocessedUntilSent() {
        Instant now = Instant.now();
        List<NotificationDocument> docs = List.of(
                insertDoc(NotificationStatus.PENDING, 0, 0, null),
                insertDoc(NotificationStatus.RETRYING, 1, 1, null),
                insertDoc(NotificationStatus.PROCESSING, 0, 1, now.minus(Duration.ofMinutes(1))),
                insertDoc(NotificationStatus.PROCESSING, 0, 1, null));
        docs.forEach(doc -> putInDlq(doc.id()));
        awaitReady(4);

        assertReport(adminPost(REPROCESS_10), 10, 4, 0, 0);

        for (NotificationDocument doc : docs) {
            JsonNode sent = awaitStatus(doc.id(), NotificationStatus.SENT, Duration.ofSeconds(15));
            List<JsonNode> reprocessed = reprocessedEntries(sent);
            assertThat(reprocessed).as("REPROCESSED de %s (%s)", doc.id(), doc.status()).hasSize(1);
            assertThat(reprocessed.get(0).path("errorMessage").asText())
                    .contains("status anterior: " + doc.status().name());
        }
        awaitDlq(0);
    }

    @Test
    void processingWithAnActiveLeaseGoesToTheDlqTailUntilTheLeaseExpires() {
        NotificationDocument doc = insertDoc(NotificationStatus.PROCESSING, 0, 1,
                Instant.now().plus(Duration.ofMinutes(5)));
        putInDlq(doc.id());
        awaitReady(1);

        // Lease vigente: um worker pode estar com ele (D-02), então nada é escrito
        assertReport(adminPost(REPROCESS_10), 10, 0, 1, 0);
        JsonNode untouched = get("/notifications/" + doc.id()).body();
        assertThat(untouched.path("status").asText()).isEqualTo("PROCESSING");
        assertThat(reprocessedEntries(untouched)).isEmpty();
        awaitDlq(1);

        // O lease vence: o próximo reprocess o retoma até SENT
        mongo().updateFirst(Query.query(Criteria.where(Fields.ID).is(doc.id())),
                Update.update(Fields.LEASE_UNTIL, Instant.now().minus(Duration.ofMinutes(1))),
                NotificationDocument.class);
        assertReport(adminPost(REPROCESS_10), 10, 1, 0, 0);
        JsonNode sent = awaitStatus(doc.id(), NotificationStatus.SENT, Duration.ofSeconds(15));
        assertThat(reprocessedEntries(sent)).hasSize(1);
        awaitDlq(0);
    }

    @Test
    void sentAndMissingDocumentsAreDiscarded() {
        NotificationDocument sent = insertDoc(NotificationStatus.SENT, 0, 1, null);
        putInDlq(sent.id());
        putInDlq(UUID.randomUUID().toString()); // mensagem sem documento
        awaitReady(2);

        assertReport(adminPost(REPROCESS_10), 10, 0, 0, 2);

        awaitDlq(0);
        JsonNode after = get("/notifications/" + sent.id()).body();
        assertThat(after.path("status").asText()).isEqualTo("SENT");
        assertThat(reprocessedEntries(after)).isEmpty();
    }

    @Test
    void invalidBodyIsPreservedAtTheDlqTail() {
        putRawInDlq("isto não é json".getBytes(StandardCharsets.UTF_8));
        awaitReady(1);

        assertReport(adminPost(REPROCESS_10), 10, 0, 1, 0);

        awaitDlq(1);
    }

    @Test
    void skippedMessageIsNeverReadAgainInTheSameCall() {
        NotificationDocument doc = insertDoc(NotificationStatus.PROCESSING, 0, 1,
                Instant.now().plus(Duration.ofMinutes(5)));
        putInDlq(doc.id());
        awaitReady(1);

        // O orçamento é min(max, prontas no início) = 1: a cópia no fim não é relida (D-04)
        assertReport(adminPost("/admin/dlq/email/reprocess?max=100"), 100, 0, 1, 0);

        awaitDlq(1);
    }

    @Test
    void maxLimitsHowManyMessagesOneCallConsumes() {
        List<NotificationDocument> docs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            NotificationDocument doc = insertDoc(NotificationStatus.DEAD_LETTERED, 3, 4, null);
            docs.add(doc);
            putInDlq(doc.id());
            awaitReady(i + 1);
        }

        assertReport(adminPost("/admin/dlq/email/reprocess?max=1"), 1, 1, 0, 0);

        // Fila FIFO: o primeiro da DLQ chega a SENT; os outros dois continuam na DLQ
        awaitStatus(docs.get(0).id(), NotificationStatus.SENT, Duration.ofSeconds(15));
        awaitDlq(2);
        for (NotificationDocument waiting : docs.subList(1, 3)) {
            assertThat(get("/notifications/" + waiting.id()).body().path("status").asText())
                    .isEqualTo("DEAD_LETTERED");
        }
    }

    @Test
    void concurrentReprocessCallsNeverReprocessTheSameMessageTwice() throws Exception {
        List<NotificationDocument> docs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            NotificationDocument doc = insertDoc(NotificationStatus.DEAD_LETTERED, 3, 4, null);
            docs.add(doc);
            putInDlq(doc.id());
        }
        awaitReady(6);

        // Duas chamadas liberadas ao mesmo tempo no mesmo canal
        CountDownLatch start = new CountDownLatch(1);
        List<HttpResult> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HttpResult> first = pool.submit(() -> {
                start.await();
                return adminPost(REPROCESS_10);
            });
            Future<HttpResult> second = pool.submit(() -> {
                start.await();
                return adminPost(REPROCESS_10);
            });
            start.countDown();
            results.add(first.get(30, TimeUnit.SECONDS));
            results.add(second.get(30, TimeUnit.SECONDS));
        }

        int reprocessed = 0;
        int skipped = 0;
        int discarded = 0;
        for (HttpResult result : results) {
            assertThat(result.status()).as(result.raw()).isEqualTo(200);
            assertThat(result.body().path("requested").asInt()).as(result.raw()).isEqualTo(10);
            reprocessed += result.body().path("reprocessed").asInt();
            skipped += result.body().path("skipped").asInt();
            discarded += result.body().path("discarded").asInt();
        }
        // basic.get entrega cada mensagem a um só canal: a soma é exatamente o tamanho da DLQ
        assertThat(reprocessed).as("soma de reprocessed").isEqualTo(6);
        assertThat(skipped).as("soma de skipped").isZero();
        assertThat(discarded).as("soma de discarded").isZero();

        for (NotificationDocument doc : docs) {
            JsonNode sent = awaitStatus(doc.id(), NotificationStatus.SENT, Duration.ofSeconds(15));
            assertThat(reprocessedEntries(sent)).as("REPROCESSED de %s", doc.id()).hasSize(1);
        }
        awaitDlq(0);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Insere direto no Mongo um documento de e-mail para {@code ana@exemplo.com} (template
     * {@code welcome}, {@code name: Ana}) no estado pedido. Com lease, {@code claimedAt} = agora.
     */
    private static NotificationDocument insertDoc(NotificationStatus status, int retryCount, int attemptCount,
            Instant leaseUntil) {
        Instant now = Instant.now();
        NotificationDocument doc = new NotificationDocument(UUID.randomUUID().toString(), Channel.EMAIL,
                "ana@exemplo.com", "welcome", Map.of("name", "Ana"), status, retryCount, attemptCount, null, null,
                List.of(), now, now, leaseUntil != null ? now : null, leaseUntil,
                status == NotificationStatus.SENT ? now : null);
        return mongo().insert(doc);
    }

    private static RabbitTemplate rabbit() {
        return api().getBean(RabbitTemplate.class);
    }

    /** Põe na DLQ de e-mail o token da notificação, como a cópia TRANSIENT do worker. */
    private static void putInDlq(String id) {
        rabbit().convertAndSend(NotifyTopology.DLX_EXCHANGE, Channel.EMAIL.dlqKey(),
                new NotificationMessage(id, Channel.EMAIL, "welcome", Instant.now()), message -> {
                    message.getMessageProperties().setMessageId(id);
                    message.getMessageProperties().setHeader(NotifyHeaders.FAILURE_CLASS, "TRANSIENT");
                    return message;
                });
    }

    /** Põe na DLQ de e-mail um corpo arbitrário. */
    private static void putRawInDlq(byte[] body) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(UUID.randomUUID().toString());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setHeader(NotifyHeaders.FAILURE_CLASS, "TRANSIENT");
        rabbit().send(NotifyTopology.DLX_EXCHANGE, Channel.EMAIL.dlqKey(), new Message(body, props));
    }

    private static void awaitReady(int expected) {
        await("DLQ com " + expected + " prontas")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> readyCount(DLQ) == expected);
    }

    /** A DLQ termina com {@code ready} prontas e 0 sem ack (estatística do painel, ~5s). */
    private static void awaitDlq(int ready) {
        await("DLQ com " + ready + " prontas e 0 sem ack")
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> readyCount(DLQ) == ready && unackedCount(DLQ) == 0);
    }

    private static void assertReport(HttpResult result, int requested, int reprocessed, int skipped, int discarded) {
        assertThat(result.status()).as(result.raw()).isEqualTo(200);
        JsonNode body = result.body();
        assertThat(body.path("requested").asInt()).as(result.raw()).isEqualTo(requested);
        assertThat(body.path("reprocessed").asInt()).as(result.raw()).isEqualTo(reprocessed);
        assertThat(body.path("skipped").asInt()).as(result.raw()).isEqualTo(skipped);
        assertThat(body.path("discarded").asInt()).as(result.raw()).isEqualTo(discarded);
    }

    private static List<JsonNode> reprocessedEntries(JsonNode notification) {
        List<JsonNode> entries = new ArrayList<>();
        notification.path("attempts").forEach(attempt -> {
            if ("REPROCESSED".equals(attempt.path("outcome").asText())) {
                entries.add(attempt);
            }
        });
        return entries;
    }
}
