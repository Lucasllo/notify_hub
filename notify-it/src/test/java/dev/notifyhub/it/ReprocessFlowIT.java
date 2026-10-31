package dev.notifyhub.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.persistence.Collections;
import dev.notifyhub.it.support.EndToEndSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * Ciclo completo do reprocessamento pela DLQ (TST-02, critérios 1 e 2 do ROADMAP), com RabbitMQ e
 * MongoDB reais e a API e o worker de verdade: uma notificação esgota a escada de retry, termina
 * DEAD_LETTERED com a mensagem em {@code notify.email.dlq}, volta pelo
 * {@code POST /admin/dlq/email/reprocess} do operador e chega a SENT com o histórico completo.
 *
 * <p><b>TTLs curtos.</b> O profile {@code it} usa a escada {@code 200ms,500ms,1s}, então a escada
 * inteira roda em poucos segundos. Toda espera é com Awaitility, nunca com pausa fixa.
 *
 * <p><b>Por que exatamente 3 níveis (Armadilha 11).</b> O destinatário mágico
 * {@code fail-until-reprocess@fail.test} falha enquanto {@code attemptNumber ≤ 4} (envio inicial + 3
 * níveis) e sucede a partir da 5ª tentativa. Como o reset do reprocessamento não zera
 * {@code attemptCount}, a 5ª tentativa só existe depois do reprocess. Com outro número de níveis, a
 * notificação terminaria SENT antes da DLQ (menos níveis) ou falharia de novo depois do reprocess
 * (mais níveis), e o teste deixaria de provar o ciclo.
 *
 * <p><b>Isolamento.</b> Cada teste começa com as 15 filas purgadas e {@code notifications} vazia
 * ({@code resetState()} do {@link EndToEndSupport}); os ITs rodam em série numa única JVM, então as
 * contagens exatas da DLQ não herdam mensagens de outro teste.
 */
class ReprocessFlowIT extends EndToEndSupport {

    private static final String DLQ = "notify.email.dlq";
    private static final String PROBLEM_JSON = "application/problem+json";

    @Test
    void retryLadderEndsInTheDlqAndReprocessDeliversSent() {
        String id = deadLetter("fail-until-reprocess@fail.test");

        JsonNode deadLettered = get("/notifications/" + id).body();
        JsonNode failures = deadLettered.path("attempts");
        assertThat(failures).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(failures.get(i).path("outcome").asText()).isEqualTo("TRANSIENT_FAILURE");
            assertThat(failures.get(i).path("number").asInt()).isEqualTo(i + 1);
        }

        HttpResult reprocess = adminPost("/admin/dlq/email/reprocess?max=10");
        assertReport(reprocess, 10, 1, 0, 0);

        JsonNode sent = awaitStatus(id, NotificationStatus.SENT, Duration.ofSeconds(15));
        assertThat(sent.path("retryCount").asInt()).isZero();
        JsonNode attempts = sent.path("attempts");
        assertThat(attempts).hasSize(6);
        for (int i = 0; i < 4; i++) {
            assertThat(attempts.get(i).path("outcome").asText()).isEqualTo("TRANSIENT_FAILURE");
        }
        JsonNode reprocessed = attempts.get(4);
        assertThat(reprocessed.path("outcome").asText()).isEqualTo("REPROCESSED");
        assertThat(reprocessed.path("number").asInt()).isZero();
        assertThat(reprocessed.path("errorMessage").asText()).contains("DEAD_LETTERED");
        JsonNode success = attempts.get(5);
        assertThat(success.path("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(success.path("number").asInt()).isEqualTo(5);

        // O reset não zera attemptCount: a 5ª tentativa é a do sucesso
        assertThat(rawDocument(id).getInteger("attemptCount")).isEqualTo(5);

        awaitDlqEmpty();
    }

    @Test
    void secondReprocessCallIsANoOp() {
        String id = deadLetter("fail-until-reprocess@fail.test");
        assertReport(adminPost("/admin/dlq/email/reprocess?max=10"), 10, 1, 0, 0);
        awaitStatus(id, NotificationStatus.SENT, Duration.ofSeconds(15));
        awaitDlqEmpty();

        // Repetir com a DLQ vazia não muda nada
        assertReport(adminPost("/admin/dlq/email/reprocess?max=10"), 10, 0, 0, 0);

        HttpResult after = get("/notifications/" + id);
        assertThat(after.body().path("status").asText()).isEqualTo("SENT");
        assertThat(outcomes(after.body().path("attempts"))).containsOnlyOnce("REPROCESSED");
    }

    @Test
    void rejectedAdminCallsNeverMoveTheDlq() {
        String id = deadLetter("always-transient@fail.test");
        List<String> attemptsBefore = outcomes(get("/notifications/" + id).body().path("attempts"));
        assertThat(attemptsBefore).hasSize(4).doesNotContain("REPROCESSED");

        // Sem chave e com chave errada: 401 antes de tocar na DLQ
        assertProblem(post("/admin/dlq/email/reprocess?max=10", ""), 401, null);
        assertProblem(post("/admin/dlq/email/reprocess?max=10", "", "X-API-Key", "errada"), 401, null);

        // Com a chave, canal e max inválidos: 400 com o campo do erro
        assertProblem(adminPost("/admin/dlq/push/reprocess"), 400, "canal");
        assertProblem(adminPost("/admin/dlq/email/reprocess?max=0"), 400, "max");
        assertProblem(adminPost("/admin/dlq/email/reprocess?max=1001"), 400, "max");
        assertProblem(adminPost("/admin/dlq/email/reprocess?max=abc"), 400, "max");

        // A DLQ continua com a mesma mensagem pronta e nada pendurado sem ack
        assertThat(readyCount(DLQ)).isEqualTo(1);
        await("DLQ sem entregas pendentes de ack")
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> unackedCount(DLQ) == 0 && readyCount(DLQ) == 1);

        HttpResult after = get("/notifications/" + id);
        assertThat(after.body().path("status").asText()).isEqualTo("DEAD_LETTERED");
        assertThat(outcomes(after.body().path("attempts"))).isEqualTo(attemptsBefore);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Cria uma notificação de e-mail pelo HTTP para um destinatário mágico, espera DEAD_LETTERED e a
     * mensagem pronta na DLQ, e devolve o id.
     */
    private String deadLetter(String recipient) {
        HttpResult created = post("/notifications", """
                {"channel":"EMAIL","recipient":"%s","templateCode":"welcome","variables":{"name":"Ana"}}
                """.formatted(recipient));
        assertThat(created.status()).as(created.raw()).isEqualTo(202);
        String id = created.body().path("id").asText();

        awaitStatus(id, NotificationStatus.DEAD_LETTERED, Duration.ofSeconds(15));
        await("mensagem de " + id + " pronta na DLQ")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> readyCount(DLQ) == 1);
        return id;
    }

    private static void assertReport(HttpResult result, int requested, int reprocessed, int skipped, int discarded) {
        assertThat(result.status()).as(result.raw()).isEqualTo(200);
        JsonNode body = result.body();
        assertThat(body.path("requested").asInt()).as(result.raw()).isEqualTo(requested);
        assertThat(body.path("reprocessed").asInt()).as(result.raw()).isEqualTo(reprocessed);
        assertThat(body.path("skipped").asInt()).as(result.raw()).isEqualTo(skipped);
        assertThat(body.path("discarded").asInt()).as(result.raw()).isEqualTo(discarded);
    }

    private static void assertProblem(HttpResult result, int status, String field) {
        assertThat(result.status()).as(result.raw()).isEqualTo(status);
        assertThat(result.header("Content-Type")).as(result.raw()).startsWith(PROBLEM_JSON);
        if (field != null) {
            assertThat(result.body().path("errors").get(0).path("field").asText()).as(result.raw()).isEqualTo(field);
        }
    }

    /** A DLQ termina sem mensagens prontas nem sem ack (estatística do painel, atualizada a cada ~5s). */
    private static void awaitDlqEmpty() {
        await("DLQ vazia, sem prontas nem sem ack")
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> readyCount(DLQ) == 0 && unackedCount(DLQ) == 0);
    }

    private static List<String> outcomes(JsonNode attempts) {
        List<String> result = new ArrayList<>();
        attempts.forEach(attempt -> result.add(attempt.path("outcome").asText()));
        return result;
    }

    private static Document rawDocument(String id) {
        Document doc = mongo().findById(id, Document.class, Collections.NOTIFICATIONS);
        assertThat(doc).as("documento " + id).isNotNull();
        return doc;
    }
}
