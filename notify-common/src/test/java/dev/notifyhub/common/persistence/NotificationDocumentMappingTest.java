package dev.notifyhub.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.notifyhub.common.domain.AttemptOutcome;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mapping.MappingException;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

/** Mapeamento do documento {@code notifications} sem banco (conversor do Spring Data puro). */
class NotificationDocumentMappingTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private final MappingMongoConverter converter = newConverter();

    private static MappingMongoConverter newConverter() {
        MongoCustomConversions conversions = new MongoCustomConversions(List.of());
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        return converter;
    }

    private Document write(NotificationDocument doc) {
        Document bson = new Document();
        converter.write(doc, bson);
        return bson;
    }

    private static NotificationDocument pending() {
        return NotificationDocument.newPending(
                "id-1", Channel.EMAIL, "ana@exemplo.com", "welcome", Map.of("name", "Ana"), NOW);
    }

    @Test
    void newPendingStartsWithZeroCountersAndEmptyHistory() {
        NotificationDocument doc = pending();

        assertThat(doc.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(doc.retryCount()).isZero();
        assertThat(doc.attemptCount()).isZero();
        assertThat(doc.attempts()).isEmpty();
        assertThat(doc.createdAt()).isEqualTo(NOW);
        assertThat(doc.updatedAt()).isEqualTo(NOW);
        assertThat(doc.rendered()).isNull();
        assertThat(doc.lastError()).isNull();
        assertThat(doc.claimedAt()).isNull();
        assertThat(doc.leaseUntil()).isNull();
        assertThat(doc.sentAt()).isNull();
    }

    @Test
    void writesUnderscoreIdAndEnumsAsStrings() {
        Document bson = write(pending());

        assertThat(bson.get("_id")).isEqualTo("id-1");
        assertThat(bson).doesNotContainKey("id");
        assertThat(bson.get("status")).isEqualTo("PENDING");
        assertThat(bson.get("channel")).isEqualTo("EMAIL");
        assertThat(bson.get("retryCount")).isEqualTo(0);
        assertThat(bson.get("attemptCount")).isEqualTo(0);
        assertThat(bson.get("attempts")).isInstanceOf(List.class);
        assertThat((List<?>) bson.get("attempts")).isEmpty();
    }

    @Test
    void fieldConstantsMatchStoredKeys() {
        Document bson = write(pending());

        assertThat(bson).containsKeys(
                NotificationDocument.Fields.ID,
                NotificationDocument.Fields.STATUS,
                NotificationDocument.Fields.CHANNEL,
                NotificationDocument.Fields.RETRY_COUNT,
                NotificationDocument.Fields.ATTEMPT_COUNT,
                NotificationDocument.Fields.ATTEMPTS,
                NotificationDocument.Fields.CREATED_AT,
                NotificationDocument.Fields.UPDATED_AT);
    }

    @Test
    void roundTripReturnsEqualRecord() {
        NotificationDocument original = pending();

        NotificationDocument read = converter.read(NotificationDocument.class, write(original));

        assertThat(read).isEqualTo(original);
    }

    @Test
    void roundTripKeepsAttemptsRenderedAndLastError() {
        AttemptEntry attempt = new AttemptEntry(1, 0, NOW, 12L, AttemptOutcome.SUCCESS,
                null, null, null, "VirtualThread[#42]/runnable@ForkJoinPool-1-worker-1");
        NotificationDocument sent = new NotificationDocument("id-2", Channel.SMS, "+5511999999999", "welcome",
                Map.of("name", "Ana"), NotificationStatus.SENT, 0, 1,
                new RenderedContent(null, "Oi Ana"), null, List.of(attempt),
                NOW, NOW.plusSeconds(1), NOW, NOW.plusSeconds(30), NOW.plusSeconds(1));

        Document bson = write(sent);
        NotificationDocument read = converter.read(NotificationDocument.class, bson);

        assertThat(read).isEqualTo(sent);
        Document storedAttempt = (Document) ((List<?>) bson.get("attempts")).get(0);
        assertThat(storedAttempt.get("outcome")).isEqualTo("SUCCESS");
        assertThat(storedAttempt.get("thread")).isEqualTo(attempt.thread());
    }

    @Test
    void variableKeyWithDotIsRejectedByConverter() {
        NotificationDocument doc = NotificationDocument.newPending(
                "id-3", Channel.EMAIL, "ana@exemplo.com", "welcome", Map.of("a.b", "x"), NOW);

        // Por isso a API valida as chaves de variables antes de gravar (RESEARCH Armadilha 5).
        assertThatThrownBy(() -> write(doc))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("a.b");
    }
}
