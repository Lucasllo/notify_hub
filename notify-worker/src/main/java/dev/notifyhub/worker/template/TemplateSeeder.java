package dev.notifyhub.worker.template;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Grava os templates do seed ({@code classpath*:seed/templates/*.json}) na coleção
 * {@code templates}, de forma idempotente por versão (D-08):
 *
 * <ol>
 *   <li>template ausente → {@code insert} ({@link DuplicateKeyException} de outro processo é
 *       absorvida);</li>
 *   <li>versão do banco menor que a do seed → {@code findAndReplace} condicionado à versão lida;</li>
 *   <li>versão igual ou maior → nenhuma escrita (o seed nunca rebaixa um template).</li>
 * </ol>
 *
 * <p>Nunca usa upsert com filtro de versão: quando o banco já tem versão maior, o filtro não casa
 * e o upsert tentaria inserir o mesmo {@code _id} (Armadilha 2). Quem chama {@link #seed()} é o
 * runner de inicialização do worker.
 */
@Component
public class TemplateSeeder {

    static final String SEED_LOCATION = "classpath*:seed/templates/*.json";

    private static final Logger log = LoggerFactory.getLogger(TemplateSeeder.class);

    private final MongoTemplate mongo;
    private final ObjectMapper objectMapper;

    public TemplateSeeder(MongoTemplate mongo, ObjectMapper objectMapper) {
        this.mongo = mongo;
        this.objectMapper = objectMapper;
    }

    /** Resultado de uma rodada do seed. */
    public record SeedReport(int inserted, int updated, int unchanged) {
    }

    /** Lê todos os templates do seed no classpath (funciona dentro do jar), ordenados pelo código. */
    public List<TemplateDocument> loadSeed() {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(SEED_LOCATION);
        } catch (IOException e) {
            throw new UncheckedIOException("não foi possível listar " + SEED_LOCATION, e);
        }
        List<TemplateDocument> templates = new ArrayList<>(resources.length);
        for (Resource resource : resources) {
            templates.add(read(resource));
        }
        templates.sort(Comparator.comparing(TemplateDocument::id));
        return List.copyOf(templates);
    }

    /** Aplica o seed do classpath à coleção. */
    public SeedReport seed() {
        return seed(loadSeed());
    }

    /** Aplica a regra de versão de D-08 a cada template informado. */
    public SeedReport seed(List<TemplateDocument> templates) {
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        for (TemplateDocument template : templates) {
            switch (apply(template)) {
                case INSERTED -> inserted++;
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
            }
        }
        SeedReport report = new SeedReport(inserted, updated, unchanged);
        log.info("Seed de templates aplicado: {} inserido(s), {} atualizado(s), {} sem mudança",
                report.inserted(), report.updated(), report.unchanged());
        return report;
    }

    private Outcome apply(TemplateDocument template) {
        TemplateDocument existing = mongo.findById(template.id(), TemplateDocument.class);
        if (existing == null) {
            try {
                mongo.insert(template);
                return Outcome.INSERTED;
            } catch (DuplicateKeyException e) {
                log.info("Template {} já inserido por outro processo", template.id());
                return Outcome.UNCHANGED;
            }
        }
        if (existing.version() < template.version()) {
            TemplateDocument replaced = mongo.findAndReplace(
                    query(where("_id").is(template.id()).and("version").is(existing.version())),
                    template);
            if (replaced == null) {
                log.info("Template {} alterado por outro processo durante o seed", template.id());
                return Outcome.UNCHANGED;
            }
            return Outcome.UPDATED;
        }
        return Outcome.UNCHANGED;
    }

    private TemplateDocument read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            TemplateDocument template = objectMapper.readValue(in, TemplateDocument.class);
            if (template.id() == null || template.id().isBlank()
                    || template.variants() == null || template.variants().isEmpty()) {
                throw new IllegalStateException("seed inválido: " + resource.getFilename());
            }
            return template;
        } catch (IOException e) {
            throw new UncheckedIOException("seed ilegível: " + resource.getFilename(), e);
        }
    }

    private enum Outcome { INSERTED, UPDATED, UNCHANGED }
}
