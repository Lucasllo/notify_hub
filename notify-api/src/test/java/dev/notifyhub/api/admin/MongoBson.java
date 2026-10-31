package dev.notifyhub.api.admin;

import dev.notifyhub.common.persistence.NotificationDocument;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.convert.UpdateMapper;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/**
 * Converte um {@link UpdateDefinition} no BSON que o driver mandaria ao MongoDB, com o mesmo mapper
 * do {@code MongoTemplate} (enums viram string, records viram documentos, os modificadores
 * {@code $each}/{@code $slice} do {@code $push} viram um documento). Sem banco. Mesmo molde do
 * {@code MongoBson} dos testes do worker.
 */
final class MongoBson {

    private static final MongoMappingContext CONTEXT;
    private static final UpdateMapper UPDATE_MAPPER;

    static {
        MongoCustomConversions conversions = new MongoCustomConversions(List.of());
        CONTEXT = new MongoMappingContext();
        CONTEXT.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        CONTEXT.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, CONTEXT);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        UPDATE_MAPPER = new UpdateMapper(converter);
    }

    private MongoBson() {
    }

    static Document update(UpdateDefinition update) {
        return UPDATE_MAPPER.getMappedObject(update.getUpdateObject(),
                CONTEXT.getRequiredPersistentEntity(NotificationDocument.class));
    }
}
