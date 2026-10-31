package dev.notifyhub.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.notifyhub.api.application.NotificationQueryService;
import dev.notifyhub.common.domain.AttemptOutcome;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.FailureClass;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.persistence.AttemptEntry;
import dev.notifyhub.common.persistence.LastError;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.common.persistence.RenderedContent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Fatia web da leitura operacional (API-05, D-09..D-11): controller e serviço reais, Mongo simulado.
 * Confere a Query montada (filtro, ordenação, skip/limit em long e projeção), o envelope da página, o
 * item resumido e o 400 dos parâmetros inválidos sem tocar no Mongo. Sem Docker.
 */
@WebMvcTest(NotificationQueryController.class)
@Import(NotificationQueryService.class)
class NotificationQueryControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-31T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MongoTemplate mongo;

    private static NotificationDocument doc(String id, NotificationStatus status, LastError lastError) {
        AttemptEntry attempt = new AttemptEntry(1, 0, T0, 12, AttemptOutcome.PERMANENT_FAILURE, "X", "falhou",
                FailureClass.PERMANENT, "VirtualThread[#1]");
        return new NotificationDocument(id, Channel.EMAIL, "ana@exemplo.com", "welcome", Map.of("name", "Ana"),
                status, 1, 2, new RenderedContent("Oi", "Olá, Ana"), lastError, List.of(attempt), T0, T0.plusSeconds(1),
                null, null, null, "chave-1", "hash-1");
    }

    private void stubPage(long total, NotificationDocument... docs) {
        when(mongo.count(any(Query.class), eq(NotificationDocument.class))).thenReturn(total);
        when(mongo.find(any(Query.class), eq(NotificationDocument.class))).thenReturn(List.of(docs));
    }

    private Query capturedFindQuery() {
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(captor.capture(), eq(NotificationDocument.class));
        return captor.getValue();
    }

    private void assertMongoUntouched() {
        verify(mongo, never()).find(any(Query.class), eq(NotificationDocument.class));
        verify(mongo, never()).count(any(Query.class), eq(NotificationDocument.class));
    }

    // ---------------------------------------------------------------- 200: envelope e Query

    @Test
    void withoutParametersListsTheFirstPageOfTwentyWithTheEnvelope() throws Exception {
        stubPage(3, doc("n1", NotificationStatus.SENT, null), doc("n2", NotificationStatus.PENDING, null));

        mvc.perform(get("/notifications"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].id").value("n1"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1));

        Query query = capturedFindQuery();
        assertThat(query.getQueryObject()).isEmpty();
        assertThat(query.getSortObject()).isEqualTo(new Document("createdAt", -1).append("_id", -1));
        assertThat(new ArrayList<>(query.getSortObject().keySet())).containsExactly("createdAt", "_id");
        assertThat(query.getSkip()).isZero();
        assertThat(query.getLimit()).isEqualTo(20);
    }

    @Test
    void projectionIncludesExactlyTheSummaryFields() throws Exception {
        stubPage(1, doc("n1", NotificationStatus.SENT, null));

        mvc.perform(get("/notifications")).andExpect(status().isOk());

        assertThat(capturedFindQuery().getFieldsObject().keySet()).containsExactlyInAnyOrder(
                "status", "channel", "recipient", "templateCode", "retryCount", "lastError.code", "createdAt",
                "updatedAt");
    }

    /** O total conta o filtro inteiro: o count roda antes de skip/limit entrarem na Query. */
    @Test
    void totalIsCountedBeforeSkipAndLimit() throws Exception {
        AtomicLong skipAtCount = new AtomicLong(-1);
        AtomicLong limitAtCount = new AtomicLong(-1);
        when(mongo.count(any(Query.class), eq(NotificationDocument.class))).thenAnswer(inv -> {
            Query q = inv.getArgument(0);
            skipAtCount.set(q.getSkip());
            limitAtCount.set(q.getLimit());
            return 42L;
        });
        when(mongo.find(any(Query.class), eq(NotificationDocument.class))).thenReturn(List.of());

        mvc.perform(get("/notifications").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(42))
                .andExpect(jsonPath("$.totalPages").value(5));

        assertThat(skipAtCount.get()).isZero();
        assertThat(limitAtCount.get()).isZero();
    }

    @Test
    void statusChannelPageAndSizeBecomeFilterSkipAndLimit() throws Exception {
        stubPage(30);

        mvc.perform(get("/notifications").param("status", "FAILED").param("channel", "EMAIL")
                        .param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalPages").value(6));

        Query query = capturedFindQuery();
        Document filter = query.getQueryObject();
        assertThat(filter.keySet()).containsExactlyInAnyOrder("status", "channel");
        assertThat(String.valueOf(filter.get("status"))).isEqualTo("FAILED");
        assertThat(String.valueOf(filter.get("channel"))).isEqualTo("EMAIL");
        assertThat(query.getSkip()).isEqualTo(10L);
        assertThat(query.getLimit()).isEqualTo(5);
    }

    @Test
    void emptyStatusAndChannelMeanNoFilter() throws Exception {
        stubPage(1, doc("n1", NotificationStatus.SENT, null));

        mvc.perform(get("/notifications").param("status", "").param("channel", " "))
                .andExpect(status().isOk());

        assertThat(capturedFindQuery().getQueryObject()).isEmpty();
    }

    /**
     * {@code page * size} em long: 2147483647 × 100 = 214748364700, sem estouro de int. A página fica
     * além do total, então sai vazia com o total real, sem mandar ao Mongo um skip que o Spring Data
     * truncaria para int.
     */
    @Test
    void deepPageUsesALongSkipAndAnswersAnEmptyPageWithTheRealTotal() throws Exception {
        stubPage(3);

        mvc.perform(get("/notifications").param("page", "2147483647").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page").value(2147483647))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1));

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongo).count(captor.capture(), eq(NotificationDocument.class));
        assertThat(captor.getValue().getSkip()).isEqualTo(214_748_364_700L);
        verify(mongo, never()).find(any(Query.class), eq(NotificationDocument.class));
    }

    @Test
    void emptyCollectionHasZeroPages() throws Exception {
        stubPage(0);

        mvc.perform(get("/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    // ---------------------------------------------------------------- item resumido (D-11)

    @Test
    void itemIsASummaryWithOnlyTheLastErrorCodeAndNoContentNorHistory() throws Exception {
        stubPage(2,
                doc("n1", NotificationStatus.FAILED,
                        new LastError("TEMPLATE_NOT_FOUND", "template inexistente", FailureClass.PERMANENT, T0)),
                doc("n2", NotificationStatus.SENT, null));

        mvc.perform(get("/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("n1"))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$.content[0].recipient").value("ana@exemplo.com"))
                .andExpect(jsonPath("$.content[0].templateCode").value("welcome"))
                .andExpect(jsonPath("$.content[0].retryCount").value(1))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-10-31T12:00:00Z"))
                .andExpect(jsonPath("$.content[0].updatedAt").value("2026-10-31T12:00:01Z"))
                .andExpect(jsonPath("$.content[0].lastError.code").value("TEMPLATE_NOT_FOUND"))
                .andExpect(jsonPath("$.content[0].lastError.message").doesNotExist())
                .andExpect(jsonPath("$.content[0].lastError.failureClass").doesNotExist())
                .andExpect(jsonPath("$.content[0].attempts").doesNotExist())
                .andExpect(jsonPath("$.content[0].rendered").doesNotExist())
                .andExpect(jsonPath("$.content[0].variables").doesNotExist())
                .andExpect(jsonPath("$.content[0].idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.content[0].requestHash").doesNotExist())
                .andExpect(jsonPath("$.content[0].attemptCount").doesNotExist())
                .andExpect(jsonPath("$.content[1].lastError").value(nullValue()));
    }

    // ---------------------------------------------------------------- 400 (D-10)

    @ParameterizedTest
    @CsvSource({
            "size, 0",
            "size, 101",
            "size, -1",
            "page, -1",
            "page, abc",
            "size, abc",
            "status, FOO",
            "status, sent",
            "channel, email",
            "channel, PUSH"
    })
    void invalidParameterIs400WithTheFieldAndNeverQueriesMongo(String name, String value) throws Exception {
        mvc.perform(get("/notifications").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:notify-hub:problem:validation-error"))
                .andExpect(jsonPath("$.errors[*].field", hasItem(name)))
                .andExpect(jsonPath("$.instance").value("/notifications"));
        assertMongoUntouched();
    }

    @Test
    void invalidStatusListsTheAcceptedValues() throws Exception {
        mvc.perform(get("/notifications").param("status", "FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"))
                .andExpect(jsonPath("$.errors[0].message").value(
                        "valores aceitos: PENDING, PROCESSING, SENT, RETRYING, FAILED, DEAD_LETTERED, PUBLISH_FAILED"));
    }

    @Test
    void invalidChannelListsTheAcceptedValues() throws Exception {
        mvc.perform(get("/notifications").param("channel", "email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("channel"))
                .andExpect(jsonPath("$.errors[0].message").value("valores aceitos: EMAIL, SMS, WEBHOOK"));
    }

    @Test
    void outOfRangePageAndSizeExplainTheLimits() throws Exception {
        mvc.perform(get("/notifications").param("page", "-1").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[?(@.field == 'page')].message",
                        containsInAnyOrder("deve ser maior ou igual a 0")))
                .andExpect(jsonPath("$.errors[?(@.field == 'size')].message",
                        containsInAnyOrder("deve estar entre 1 e 100")));
        assertMongoUntouched();
    }

    @Test
    void severalInvalidParametersComeInASingle400() throws Exception {
        mvc.perform(get("/notifications").param("size", "0").param("status", "FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("size", "status")))
                .andExpect(jsonPath("$.detail", containsString("inválidos")));
        assertMongoUntouched();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "100"})
    void sizeAtTheLimitsIsAccepted(String size) throws Exception {
        stubPage(1, doc("n1", NotificationStatus.SENT, null));

        mvc.perform(get("/notifications").param("size", size))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(Integer.parseInt(size)));

        assertThat(capturedFindQuery().getLimit()).isEqualTo(Integer.parseInt(size));
    }
}
