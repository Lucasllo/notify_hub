package dev.notifyhub.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.notifyhub.common.persistence.Collections;
import dev.notifyhub.it.support.EndToEndSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Documentação OpenAPI da API real (API-08): {@code /v3/api-docs} e o Swagger UI do springdoc 2.8.17.
 *
 * <p>A prova fica aqui, e não num {@code @SpringBootTest} do {@code notify-api}, porque a API só sobe
 * com Rabbit e Mongo, e isso quebraria o {@code ./mvnw test} sem Docker (RESEARCH Padrão 8).
 */
class ApiDocsIT extends EndToEndSupport {

    private static final String REPROCESS = "/admin/dlq/{canal}/reprocess";
    private static final String REPUBLISH_FAILED = "/admin/notifications/republish-failed";

    @Test
    void apiDocsListEveryEndpointAndTheAdminKeyScheme() {
        // sem X-API-Key: o interceptor só cobre /admin/**
        HttpResult docs = get("/v3/api-docs");
        assertThat(docs.status()).isEqualTo(200);
        JsonNode body = docs.body();
        assertThat(body).as("corpo JSON do /v3/api-docs").isNotNull();
        assertThat(body.path("info").path("title").asText()).isEqualTo("Notify Hub API");

        JsonNode paths = body.path("paths");
        assertThat(pathKeys(body)).containsExactlyInAnyOrder(
                "/notifications", "/notifications/{id}", "/notifications/stats", REPROCESS, REPUBLISH_FAILED);
        assertThat(paths.path("/notifications").has("post")).as("POST /notifications").isTrue();
        assertThat(paths.path("/notifications").has("get")).as("GET /notifications").isTrue();
        assertThat(paths.path("/notifications/{id}").has("get")).as("GET /notifications/{id}").isTrue();
        assertThat(paths.path("/notifications/stats").has("get")).as("GET /notifications/stats").isTrue();
        assertThat(paths.path(REPROCESS).has("post")).as("POST reprocess").isTrue();
        assertThat(paths.path(REPUBLISH_FAILED).has("post")).as("POST republish-failed").isTrue();

        JsonNode scheme = body.path("components").path("securitySchemes").path("adminApiKey");
        assertThat(scheme.path("type").asText()).isEqualTo("apiKey");
        assertThat(scheme.path("in").asText()).isEqualTo("header");
        assertThat(scheme.path("name").asText()).isEqualTo("X-API-Key");

        for (JsonNode adminOperation : List.of(paths.path(REPROCESS).path("post"),
                paths.path(REPUBLISH_FAILED).path("post"))) {
            assertThat(adminOperation.path("security").path(0).has("adminApiKey"))
                    .as("operação admin exige adminApiKey: %s", adminOperation.path("operationId").asText())
                    .isTrue();
        }
        for (JsonNode publicOperation : publicOperations(paths)) {
            assertThat(publicOperation.has("security"))
                    .as("operação pública sem segurança: %s", publicOperation.path("operationId").asText())
                    .isFalse();
        }

        boolean idempotencyHeader = false;
        for (JsonNode parameter : paths.path("/notifications").path("post").path("parameters")) {
            if ("header".equals(parameter.path("in").asText())
                    && "Idempotency-Key".equals(parameter.path("name").asText())) {
                idempotencyHeader = true;
            }
        }
        assertThat(idempotencyHeader).as("POST /notifications documenta o header Idempotency-Key").isTrue();
    }

    @Test
    void swaggerUiIsServed() {
        HttpResult ui = get("/swagger-ui/index.html");
        assertThat(ui.status()).isEqualTo(200);
        assertThat(ui.header("Content-Type")).startsWith("text/html");
        assertThat(ui.raw()).contains("swagger-ui");
    }

    @Test
    void apiDocsAreReadOnlyAndStableAcrossConcurrentReads() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResult>> reads = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 2; i++) {
                reads.add(executor.submit(() -> {
                    start.await();
                    return get("/v3/api-docs");
                }));
            }
            start.countDown();
        }

        HttpResult first = reads.get(0).get();
        HttpResult second = reads.get(1).get();
        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(pathKeys(first.body())).hasSize(5).isEqualTo(pathKeys(second.body()));

        assertThat(mongo().count(new Query(), Collections.NOTIFICATIONS)).isZero();
        for (String queue : allQueues()) {
            assertThat(readyCount(queue)).as("mensagens prontas em %s", queue).isZero();
        }
    }

    private static Set<String> pathKeys(JsonNode apiDocs) {
        Set<String> keys = new TreeSet<>();
        apiDocs.path("paths").fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /** As 4 operações de {@code /notifications}, que não exigem chave. */
    private static List<JsonNode> publicOperations(JsonNode paths) {
        return List.of(
                paths.path("/notifications").path("post"),
                paths.path("/notifications").path("get"),
                paths.path("/notifications/{id}").path("get"),
                paths.path("/notifications/stats").path("get"));
    }
}
