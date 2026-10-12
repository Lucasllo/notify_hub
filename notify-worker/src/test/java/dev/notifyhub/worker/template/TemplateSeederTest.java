package dev.notifyhub.worker.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.notifyhub.common.domain.Channel;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/** Regra de versão do seed (D-08) sem banco: só escreve quando a versão do seed é maior. */
class TemplateSeederTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final TemplateSeeder seeder = new TemplateSeeder(mongo, new ObjectMapper());

    @Test
    void insertsTemplateMissingFromDatabase() {
        TemplateDocument seed = template(1);
        when(mongo.findById("welcome", TemplateDocument.class)).thenReturn(null);

        TemplateSeeder.SeedReport report = seeder.seed(List.of(seed));

        verify(mongo, times(1)).insert(seed);
        assertThat(report).isEqualTo(new TemplateSeeder.SeedReport(1, 0, 0));
    }

    @Test
    void doesNotWriteWhenDatabaseHasSameVersion() {
        when(mongo.findById("welcome", TemplateDocument.class)).thenReturn(template(1));

        TemplateSeeder.SeedReport report = seeder.seed(List.of(template(1)));

        verify(mongo).findById("welcome", TemplateDocument.class);
        verifyNoMoreInteractions(mongo);
        assertThat(report).isEqualTo(new TemplateSeeder.SeedReport(0, 0, 1));
    }

    @Test
    void replacesOlderVersionConditionedOnTheVersionRead() {
        TemplateDocument older = template(0);
        TemplateDocument seed = template(1);
        when(mongo.findById("welcome", TemplateDocument.class)).thenReturn(older);
        when(mongo.findAndReplace(any(Query.class), eq(seed))).thenReturn(older);

        TemplateSeeder.SeedReport report = seeder.seed(List.of(seed));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findAndReplace(query.capture(), eq(seed));
        Document filter = query.getValue().getQueryObject();
        assertThat(filter.get("_id")).isEqualTo("welcome");
        assertThat(filter.get("version")).isEqualTo(0);
        verify(mongo, times(0)).insert(any(TemplateDocument.class));
        assertThat(report).isEqualTo(new TemplateSeeder.SeedReport(0, 1, 0));
    }

    @Test
    void neverDowngradesNewerDatabaseVersion() {
        when(mongo.findById("welcome", TemplateDocument.class)).thenReturn(template(2));

        TemplateSeeder.SeedReport report = seeder.seed(List.of(template(1)));

        verify(mongo).findById("welcome", TemplateDocument.class);
        verifyNoMoreInteractions(mongo);
        assertThat(report.unchanged()).isEqualTo(1);
    }

    @Test
    void absorbsDuplicateKeyFromConcurrentInsert() {
        TemplateDocument seed = template(1);
        when(mongo.findById("welcome", TemplateDocument.class)).thenReturn(null);
        when(mongo.insert(seed)).thenThrow(new DuplicateKeyException("E11000 duplicate key"));

        TemplateSeeder.SeedReport report = seeder.seed(List.of(seed));

        assertThat(report).isEqualTo(new TemplateSeeder.SeedReport(0, 0, 1));
    }

    @Test
    void secondRunAgainstUpToDateDatabaseDoesNotWrite() {
        TemplateDocument seed = template(1);
        when(mongo.findById("welcome", TemplateDocument.class)).thenReturn(null, seed);

        seeder.seed(List.of(seed));
        TemplateSeeder.SeedReport second = seeder.seed(List.of(seed));

        verify(mongo, times(1)).insert(seed);
        verify(mongo, times(0)).findAndReplace(any(Query.class), any(TemplateDocument.class));
        assertThat(second).isEqualTo(new TemplateSeeder.SeedReport(0, 0, 1));
    }

    private static TemplateDocument template(int version) {
        return new TemplateDocument("welcome", version, List.of("name"),
                Map.of(Channel.SMS, new TemplateVariant(null, "Olá, {{name}}! v" + version)));
    }
}
