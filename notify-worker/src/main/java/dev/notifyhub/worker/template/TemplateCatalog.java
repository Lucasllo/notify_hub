package dev.notifyhub.worker.template;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Busca templates na coleção {@code templates} pelo código ({@code _id}). */
@Component
public class TemplateCatalog {

    private final MongoTemplate mongo;

    public TemplateCatalog(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * Devolve o template com o código informado.
     *
     * @throws TemplateNotFoundException se o código não existir
     */
    public TemplateDocument require(String code) {
        TemplateDocument template = mongo.findById(code, TemplateDocument.class);
        if (template == null) {
            throw TemplateNotFoundException.missing(code);
        }
        return template;
    }
}
