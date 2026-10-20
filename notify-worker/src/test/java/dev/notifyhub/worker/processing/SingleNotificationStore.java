package dev.notifyhub.worker.processing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.mongodb.client.result.UpdateResult;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.persistence.NotificationDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/**
 * Um único documento {@code notifications} em memória que aplica os filtros e updates reais do
 * worker, convertidos por {@link MongoBson}. Serve para reproduzir sem Docker sequências de entregas
 * (reentrega, token repetido) em que o resultado de uma operação muda o que a próxima casa.
 *
 * <p>Suporta só o que o worker usa hoje: no filtro, igualdade, {@code $lte}, {@code $lt},
 * {@code $in} e {@code $or} no topo; no update, {@code $set}, {@code $unset}, {@code $inc} e
 * {@code $push} com {@code $each}. Qualquer outro operador lança {@link IllegalArgumentException}:
 * se o formato do filtro mudar, o teste quebra em vez de passar calado.
 */
final class SingleNotificationStore {

    private final Document state;
    private final String recipient;

    private SingleNotificationStore(Document state, String recipient) {
        this.state = state;
        this.recipient = recipient;
    }

    /**
     * @param leaseUntil pode ser nulo; use valores relativos a {@code Instant.now()} (o claim compara
     *                   com o relógio real)
     */
    static SingleNotificationStore of(NotificationStatus status, int retryCount, int attemptCount,
                                      Instant leaseUntil, String recipient) {
        Document state = new Document("_id", "n-1")
                .append("status", status.name())
                .append("retryCount", retryCount)
                .append("attemptCount", attemptCount)
                .append("attempts", new ArrayList<Document>());
        if (leaseUntil != null) {
            state.append("leaseUntil", leaseUntil);
        }
        return new SingleNotificationStore(state, recipient);
    }

    static SingleNotificationStore of(NotificationStatus status, int retryCount, int attemptCount) {
        return of(status, retryCount, attemptCount, null, "ana@exemplo.com");
    }

    /** Stuba o claim ({@code findAndModify}) e os resultados ({@code updateFirst}) do mock. */
    SingleNotificationStore install(MongoTemplate mongo) {
        // doAnswer não invoca o mock durante o stub (um when(...) chamaria a answer anterior com nulos)
        doAnswer(invocation -> {
            Document filter = MongoBson.query(invocation.getArgument(0, Query.class));
            if (!matches(filter, state)) {
                return null;
            }
            apply(MongoBson.update(invocation.getArgument(1, UpdateDefinition.class)));
            return toNotificationDocument();
        }).when(mongo).findAndModify(any(Query.class), any(UpdateDefinition.class),
                any(FindAndModifyOptions.class), eq(NotificationDocument.class));

        doAnswer(invocation -> {
            Document filter = MongoBson.query(invocation.getArgument(0, Query.class));
            if (!matches(filter, state)) {
                return UpdateResult.acknowledged(0, 0L, null);
            }
            apply(MongoBson.update(invocation.getArgument(1, UpdateDefinition.class)));
            return UpdateResult.acknowledged(1, 1L, null);
        }).when(mongo).updateFirst(any(Query.class), any(UpdateDefinition.class), eq(NotificationDocument.class));
        return this;
    }

    String status() {
        return state.getString("status");
    }

    int retryCount() {
        return ((Number) state.get("retryCount")).intValue();
    }

    int attemptCount() {
        return ((Number) state.get("attemptCount")).intValue();
    }

    @SuppressWarnings("unchecked")
    List<Document> attempts() {
        return List.copyOf((List<Document>) state.get("attempts"));
    }

    /** Cópia do estado, para testar {@link #matches} fora do mock. */
    Document snapshot() {
        return new Document(state);
    }

    // ------------------------------------------------------------ filtro

    static boolean matches(Document filter, Document doc) {
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String key = entry.getKey();
            if (key.equals("$or")) {
                List<?> branches = (List<?>) entry.getValue();
                boolean any = branches.stream().anyMatch(b -> matches((Document) b, doc));
                if (!any) {
                    return false;
                }
            } else if (key.startsWith("$")) {
                throw new IllegalArgumentException("operador de topo não suportado: " + key);
            } else if (!fieldMatches(entry.getValue(), doc.containsKey(key), doc.get(key))) {
                return false;
            }
        }
        return true;
    }

    private static boolean fieldMatches(Object condition, boolean present, Object actual) {
        if (condition instanceof Document ops && !ops.isEmpty()
                && ops.keySet().stream().allMatch(k -> k.startsWith("$"))) {
            for (Map.Entry<String, Object> op : ops.entrySet()) {
                // campo ausente nunca satisfaz $lt, $lte nem $in
                if (!present || actual == null) {
                    return false;
                }
                boolean ok = switch (op.getKey()) {
                    case "$lte" -> compare(actual, op.getValue()) <= 0;
                    case "$lt" -> compare(actual, op.getValue()) < 0;
                    case "$in" -> ((List<?>) op.getValue()).stream()
                            .anyMatch(v -> Objects.equals(normalize(v), normalize(actual)));
                    default -> throw new IllegalArgumentException("operador não suportado: " + op.getKey());
                };
                if (!ok) {
                    return false;
                }
            }
            return true;
        }
        return present && Objects.equals(normalize(condition), normalize(actual));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object actual, Object bound) {
        Object a = normalize(actual);
        Object b = normalize(bound);
        if (a.getClass() != b.getClass()) {
            throw new IllegalArgumentException("tipos incomparáveis: " + a.getClass() + " e " + b.getClass());
        }
        return ((Comparable) a).compareTo(b);
    }

    private static Object normalize(Object value) {
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        return value;
    }

    // ------------------------------------------------------------ update

    @SuppressWarnings("unchecked")
    private void apply(Document update) {
        for (Map.Entry<String, Object> entry : update.entrySet()) {
            Document fields = (Document) entry.getValue();
            switch (entry.getKey()) {
                case "$set" -> fields.forEach((k, v) -> state.put(k, v instanceof Enum<?> e ? e.name() : v));
                case "$unset" -> fields.keySet().forEach(state::remove);
                case "$inc" -> fields.forEach((k, v) -> state.put(k,
                        ((Number) state.getOrDefault(k, 0)).intValue() + ((Number) v).intValue()));
                case "$push" -> fields.forEach((k, v) -> {
                    if (!(v instanceof Document modifiers) || !modifiers.containsKey("$each")) {
                        throw new IllegalArgumentException("$push sem $each não suportado: " + k);
                    }
                    List<Object> target = (List<Object>) state.computeIfAbsent(k, x -> new ArrayList<>());
                    target.addAll(modifiers.get("$each", List.class));
                    // $slice ignorado: o teste nunca passa de 50 tentativas
                });
                default -> throw new IllegalArgumentException("operador de update não suportado: " + entry.getKey());
            }
        }
    }

    private NotificationDocument toNotificationDocument() {
        Instant now = Instant.now();
        return new NotificationDocument(
                state.getString("_id"), Channel.EMAIL, recipient, "welcome", Map.of("name", "Ana"),
                NotificationStatus.valueOf(status()), retryCount(), attemptCount(), null, null, List.of(),
                now, now, instant(state.get("claimedAt")), instant(state.get("leaseUntil")), null);
    }

    private static Instant instant(Object value) {
        return value instanceof Date date ? date.toInstant() : (Instant) value;
    }
}
