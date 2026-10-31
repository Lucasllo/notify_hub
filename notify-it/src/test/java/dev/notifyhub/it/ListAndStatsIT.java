package dev.notifyhub.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.FailureClass;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.persistence.LastError;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.it.support.EndToEndSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Listagem paginada e stats contra o MongoDB real (API-05, API-07, D-09..D-12).
 *
 * <p>Os documentos entram direto no Mongo, sem mensagem publicada, então o worker não toca neles e os
 * status ficam exatamente como inseridos. Os {@code createdAt} são crescentes e distintos, para a ordem
 * por {@code createdAt} desc ser determinística.
 */
class ListAndStatsIT extends EndToEndSupport {

    private static final String PROBLEM_JSON = "application/problem+json";

    /** Ids dos 5 documentos de {@link #insertFiveDocs()}, na ordem de criação (d1 é o mais antigo). */
    private record FiveDocs(String d1, String d2, String d3, String d4, String d5) {
    }

    @Test
    void listFiltersPaginatesAndSortsByCreatedAtDesc() {
        FiveDocs docs = insertFiveDocs();

        HttpResult all = get("/notifications");
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.body().path("totalElements").asLong()).isEqualTo(5);
        assertThat(all.body().path("page").asInt()).isZero();
        assertThat(all.body().path("size").asInt()).isEqualTo(20);
        assertThat(all.body().path("totalPages").asInt()).isEqualTo(1);
        assertThat(ids(all)).containsExactly(docs.d5(), docs.d4(), docs.d3(), docs.d2(), docs.d1());

        HttpResult sent = get("/notifications?status=SENT");
        assertThat(sent.status()).isEqualTo(200);
        assertThat(ids(sent)).containsExactly(docs.d4(), docs.d2(), docs.d1());
        assertThat(sent.body().path("totalElements").asLong()).isEqualTo(3);

        HttpResult email = get("/notifications?channel=EMAIL");
        assertThat(email.status()).isEqualTo(200);
        assertThat(email.body().path("totalElements").asLong()).isEqualTo(3);
        assertThat(ids(email)).containsExactly(docs.d3(), docs.d2(), docs.d1());

        HttpResult secondPage = get("/notifications?status=SENT&channel=EMAIL&size=1&page=1");
        assertThat(secondPage.status()).isEqualTo(200);
        assertThat(ids(secondPage)).containsExactly(docs.d1());
        assertThat(secondPage.body().path("totalElements").asLong()).isEqualTo(2);
        assertThat(secondPage.body().path("totalPages").asInt()).isEqualTo(2);
        assertThat(secondPage.body().path("page").asInt()).isEqualTo(1);
        assertThat(secondPage.body().path("size").asInt()).isEqualTo(1);

        HttpResult emptyFilters = get("/notifications?status=&channel=");
        assertThat(emptyFilters.status()).isEqualTo(200);
        assertThat(emptyFilters.body().path("totalElements").asLong()).isEqualTo(5);

        JsonNode failedItem = null;
        for (JsonNode item : all.body().path("content")) {
            assertThat(item.has("variables")).as("variables na listagem").isFalse();
            assertThat(item.has("attempts")).as("attempts na listagem").isFalse();
            assertThat(item.has("rendered")).as("rendered na listagem").isFalse();
            assertThat(item.path("recipient").asText()).isEqualTo("ana@exemplo.com");
            assertThat(item.path("templateCode").asText()).isEqualTo("welcome");
            if (docs.d3().equals(item.path("id").asText())) {
                failedItem = item;
            } else {
                assertThat(item.path("lastError").isNull()).as("lastError nulo sem erro").isTrue();
            }
        }
        assertThat(failedItem).isNotNull();
        assertThat(failedItem.path("status").asText()).isEqualTo("FAILED");
        assertThat(failedItem.path("lastError").path("code").asText()).isEqualTo("TEMPLATE_NOT_FOUND");
        assertThat(failedItem.path("lastError").has("message")).isFalse();
    }

    /**
     * {@code page=2147483647&size=100} dá skip 214748364700: além do total, a página sai vazia com o
     * total real, e não um 500 por skip truncado para int no Spring Data.
     */
    @Test
    void deepPageBeyondTheIntRangeIsEmptyWithTheRealTotal() {
        insertFiveDocs();

        HttpResult deep = get("/notifications?page=2147483647&size=100");

        assertThat(deep.status()).isEqualTo(200);
        assertThat(deep.body().path("content").size()).isZero();
        assertThat(deep.body().path("totalElements").asLong()).isEqualTo(5);
        assertThat(deep.body().path("totalPages").asInt()).isEqualTo(1);
        assertThat(deep.body().path("page").asInt()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void invalidQueryParametersAre400() {
        for (String query : List.of("size=101", "page=-1", "status=FOO", "channel=email", "page=abc")) {
            HttpResult result = get("/notifications?" + query);
            assertThat(result.status()).as(query).isEqualTo(400);
            assertThat(result.header("Content-Type")).as(query).startsWith(PROBLEM_JSON);
            assertThat(result.body().path("type").asText()).as(query)
                    .isEqualTo("urn:notify-hub:problem:validation-error");
            String field = query.substring(0, query.indexOf('='));
            List<String> fields = new ArrayList<>();
            result.body().path("errors").forEach(e -> fields.add(e.path("field").asText()));
            assertThat(fields).as(query).contains(field);
        }
    }

    @Test
    void statsCountsByStatusAndChannelWithZeros() {
        insertFiveDocs();

        HttpResult result = get("/notifications/stats");

        assertThat(result.status()).isEqualTo(200);
        JsonNode stats = result.body();
        assertThat(stats.path("total").asLong()).isEqualTo(5);

        JsonNode byStatus = stats.path("byStatus");
        assertThat(byStatus.size()).isEqualTo(7);
        assertThat(byStatus.path("SENT").asLong()).isEqualTo(3);
        assertThat(byStatus.path("FAILED").asLong()).isEqualTo(1);
        assertThat(byStatus.path("PENDING").asLong()).isEqualTo(1);
        for (String zero : List.of("PROCESSING", "RETRYING", "DEAD_LETTERED", "PUBLISH_FAILED")) {
            assertThat(byStatus.has(zero)).as(zero).isTrue();
            assertThat(byStatus.path(zero).asLong()).as(zero).isZero();
        }

        JsonNode byChannel = stats.path("byChannel");
        assertThat(byChannel.size()).isEqualTo(3);
        assertThat(byChannel.path("EMAIL").asLong()).isEqualTo(3);
        assertThat(byChannel.path("SMS").asLong()).isEqualTo(1);
        assertThat(byChannel.path("WEBHOOK").asLong()).isEqualTo(1);

        JsonNode cross = stats.path("byChannelAndStatus");
        assertThat(cross.path("EMAIL").path("SENT").asLong()).isEqualTo(2);
        assertThat(cross.path("EMAIL").path("FAILED").asLong()).isEqualTo(1);
        assertThat(cross.path("SMS").path("SENT").asLong()).isEqualTo(1);
        assertThat(cross.path("WEBHOOK").path("PENDING").asLong()).isEqualTo(1);
        assertThat(cross.path("WEBHOOK").has("SENT")).isTrue();
        assertThat(cross.path("WEBHOOK").path("SENT").asLong()).isZero();
        assertThat(countCombinations(cross)).isEqualTo(21);
    }

    /** Coleção vazia: 200 com tudo zero. A rota literal não cai no GET por id, que daria 404. */
    @Test
    void statsOnAnEmptyCollectionIsAllZerosAndNotMistakenForAnId() {
        HttpResult result = get("/notifications/stats");

        assertThat(result.status()).isEqualTo(200);
        JsonNode stats = result.body();
        assertThat(stats.path("total").isNumber()).isTrue();
        assertThat(stats.path("total").asLong()).isZero();
        assertThat(stats.path("byStatus").size()).isEqualTo(7);
        stats.path("byStatus").forEach(v -> assertThat(v.asLong()).isZero());
        assertThat(stats.path("byChannel").size()).isEqualTo(3);
        stats.path("byChannel").forEach(v -> assertThat(v.asLong()).isZero());
        JsonNode cross = stats.path("byChannelAndStatus");
        assertThat(countCombinations(cross)).isEqualTo(21);
        cross.forEach(perStatus -> perStatus.forEach(v -> assertThat(v.asLong()).isZero()));
    }

    private static int countCombinations(JsonNode cross) {
        int combinations = 0;
        for (Channel channel : Channel.values()) {
            JsonNode perStatus = cross.path(channel.name());
            for (NotificationStatus status : NotificationStatus.values()) {
                if (perStatus.has(status.name())) {
                    combinations++;
                }
            }
        }
        return combinations;
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Os 5 documentos do cenário, com {@code createdAt} de agora − 5min a agora − 1min: d1 EMAIL SENT,
     * d2 EMAIL SENT, d3 EMAIL FAILED ({@code lastError.code} TEMPLATE_NOT_FOUND), d4 SMS SENT e d5
     * WEBHOOK PENDING.
     */
    private static FiveDocs insertFiveDocs() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String d1 = insertDoc(Channel.EMAIL, NotificationStatus.SENT, now.minus(Duration.ofMinutes(5)), null);
        String d2 = insertDoc(Channel.EMAIL, NotificationStatus.SENT, now.minus(Duration.ofMinutes(4)), null);
        String d3 = insertDoc(Channel.EMAIL, NotificationStatus.FAILED, now.minus(Duration.ofMinutes(3)),
                "TEMPLATE_NOT_FOUND");
        String d4 = insertDoc(Channel.SMS, NotificationStatus.SENT, now.minus(Duration.ofMinutes(2)), null);
        String d5 = insertDoc(Channel.WEBHOOK, NotificationStatus.PENDING, now.minus(Duration.ofMinutes(1)), null);
        return new FiveDocs(d1, d2, d3, d4, d5);
    }

    /** Insere um documento direto no Mongo, sem publicar mensagem, e devolve o id. */
    private static String insertDoc(Channel channel, NotificationStatus status, Instant createdAt,
            String lastErrorCode) {
        LastError lastError = lastErrorCode == null ? null
                : new LastError(lastErrorCode, "template inexistente", FailureClass.PERMANENT, createdAt);
        NotificationDocument doc = new NotificationDocument(UUID.randomUUID().toString(), channel,
                "ana@exemplo.com", "welcome", Map.of("name", "Ana"), status, 0, 0, null, lastError, List.of(),
                createdAt, createdAt, null, null, null);
        return mongo().insert(doc).id();
    }

    private static List<String> ids(HttpResult result) {
        List<String> ids = new ArrayList<>();
        result.body().path("content").forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }
}
