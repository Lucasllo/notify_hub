package dev.notifyhub.worker.processing;

import dev.notifyhub.common.persistence.NotificationDocument;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.core.convert.UpdateMapper;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/**
 * Converte {@link Query} e {@link UpdateDefinition} no BSON que o driver mandaria ao MongoDB, com os
 * mesmos mappers do {@code MongoTemplate} (enums viram string, records viram documentos, os
 * modificadores {@code $each}/{@code $slice} do {@code $push} viram um documento). Sem banco.
 */
final class MongoBson {

    private static final MongoMappingContext CONTEXT;
    private static final QueryMapper QUERY_MAPPER;
    private static final UpdateMapper UPDATE_MAPPER;

    static {
        MongoCustomConversions conversions = new MongoCustomConversions(List.of());
        CONTEXT = new MongoMappingContext();
        CONTEXT.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        CONTEXT.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, CONTEXT);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        QUERY_MAPPER = new QueryMapper(converter);
        UPDATE_MAPPER = new UpdateMapper(converter);
    }

    private MongoBson() {
    }

    private static MongoPersistentEntity<?> entity() {
        return CONTEXT.getRequiredPersistentEntity(NotificationDocument.class);
    }

    static Document query(Query query) {
        return QUERY_MAPPER.getMappedObject(query.getQueryObject(), entity());
    }

    static Document update(UpdateDefinition update) {
        return UPDATE_MAPPER.getMappedObject(update.getUpdateObject(), entity());
    }
}
