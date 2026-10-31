package dev.notifyhub.common.persistence;

import static dev.notifyhub.common.persistence.NotificationDocument.Fields.CHANNEL;
import static dev.notifyhub.common.persistence.NotificationDocument.Fields.CREATED_AT;
import static dev.notifyhub.common.persistence.NotificationDocument.Fields.ID;
import static dev.notifyhub.common.persistence.NotificationDocument.Fields.IDEMPOTENCY_KEY;
import static dev.notifyhub.common.persistence.NotificationDocument.Fields.STATUS;
import static org.springframework.data.domain.Sort.Direction.ASC;
import static org.springframework.data.domain.Sort.Direction.DESC;
import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;

/**
 * Cria os índices de {@code notifications} de forma explícita e idempotente (FND-04), sem depender
 * de {@code auto-index-creation}. API e worker chamam {@link #createIndexes()} no startup.
 *
 * <p>Índices na ordem ESR (igualdade → ordenação), com {@code _id} como desempate da paginação:
 * <ul>
 *   <li>{@code status_channel_createdAt}: filtro por status (e canal), mais recentes primeiro</li>
 *   <li>{@code channel_createdAt}: filtro só por canal</li>
 *   <li>{@code createdAt}: listagem sem filtro</li>
 *   <li>{@code idempotencyKey_unique}: único só onde a chave existe; documentos sem chave não
 *       disputam unicidade (D-05). É ele que resolve a corrida entre POSTs com a mesma
 *       {@code Idempotency-Key}: o segundo insert recebe {@code DuplicateKeyException}</li>
 * </ul>
 *
 * <p>Os nomes são estáveis: se já existir um índice com o mesmo nome e outras chaves, o
 * {@code createIndex} falha e o startup para de forma visível (correção: {@code docker compose down -v}).
 */
public class MongoIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public MongoIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public void createIndexes() {
        IndexOperations ops = mongoTemplate.indexOps(Collections.NOTIFICATIONS);
        List<String> created = List.of(
                ops.createIndex(new Index()
                        .on(STATUS, ASC).on(CHANNEL, ASC).on(CREATED_AT, DESC).on(ID, DESC)
                        .named("status_channel_createdAt")),
                ops.createIndex(new Index()
                        .on(CHANNEL, ASC).on(CREATED_AT, DESC).on(ID, DESC)
                        .named("channel_createdAt")),
                ops.createIndex(new Index()
                        .on(CREATED_AT, DESC).on(ID, DESC)
                        .named("createdAt")),
                ops.createIndex(new Index()
                        .on(IDEMPOTENCY_KEY, ASC)
                        .unique()
                        .partial(PartialIndexFilter.of(where(IDEMPOTENCY_KEY).exists(true)))
                        .named("idempotencyKey_unique")));
        log.info("Índices de {} garantidos: {}", Collections.NOTIFICATIONS, created);
    }
}
