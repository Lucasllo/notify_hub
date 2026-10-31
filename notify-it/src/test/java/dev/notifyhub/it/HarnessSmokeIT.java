package dev.notifyhub.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.messaging.TopologyProperties;
import dev.notifyhub.common.persistence.Collections;
import dev.notifyhub.it.support.EndToEndSupport;
import java.time.Duration;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Tracer da suíte de integração (TST-04): prova que o harness sobe a API e o worker reais na mesma
 * JVM, cada um com a própria configuração mais o profile {@code it}, e que uma notificação criada pelo
 * HTTP da API é entregue pelo worker até SENT.
 */
class HarnessSmokeIT extends EndToEndSupport {

    @Test
    void postIsDeliveredEndToEndByTheRealWorker() {
        HttpResult created = post("/notifications", """
                {"channel":"EMAIL","recipient":"ana@exemplo.com","templateCode":"welcome","variables":{"name":"Ana"}}
                """);

        assertThat(created.status()).as(created.raw()).isEqualTo(202);
        String location = created.header("Location");
        assertThat(location).matches("/notifications/[0-9a-fA-F-]{36}");
        String id = location.substring("/notifications/".length());
        assertThat(created.body().path("id").asText()).isEqualTo(id);

        JsonNode sent = awaitStatus(id, NotificationStatus.SENT, Duration.ofSeconds(15));

        assertThat(sent.path("retryCount").asInt()).isZero();
        JsonNode attempts = sent.path("attempts");
        assertThat(attempts.isArray()).isTrue();
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).path("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(attempts.get(0).path("number").asInt()).isEqualTo(1);
    }

    @Test
    void eachContextReadsItsOwnModuleConfigPlusTheItProfile() {
        ConfigurableApplicationContext worker = worker();
        ConfigurableApplicationContext api = api();
        Environment workerEnv = worker.getEnvironment();
        Environment apiEnv = api.getEnvironment();

        // cada contexto leu o application.yml do próprio módulo
        assertThat(workerEnv.getProperty("spring.application.name")).isEqualTo("notify-worker");
        assertThat(apiEnv.getProperty("spring.application.name")).isEqualTo("notify-api");

        // portas aleatórias e diferentes (server.port=0 nos dois)
        Integer workerPort = workerEnv.getProperty("local.server.port", Integer.class);
        Integer apiPort = apiEnv.getProperty("local.server.port", Integer.class);
        assertThat(workerPort).isNotNull().isPositive();
        assertThat(apiPort).isNotNull().isPositive().isNotEqualTo(workerPort);

        // escada curta do profile it nos dois contextos
        List<Duration> itLevels = List.of(Duration.ofMillis(200), Duration.ofMillis(500), Duration.ofSeconds(1));
        assertThat(worker.getBean(TopologyProperties.class).retryLevels()).isEqualTo(itLevels);
        assertThat(api.getBean(TopologyProperties.class).retryLevels()).isEqualTo(itLevels);

        // worker: profile it por cima do yml do worker
        assertThat(workerEnv.getProperty("notify.simulation.magic-recipients", Boolean.class)).isTrue();
        assertThat(workerEnv.getProperty("notify.worker.channels.email.concurrency", Integer.class)).isEqualTo(10);

        // API: yml da API mais a chave administrativa do profile it
        assertThat(apiEnv.getProperty("notify.publish.confirm-timeout", Duration.class)).isEqualTo(Duration.ofSeconds(5));
        assertThat(apiEnv.getProperty("notify.admin.api-key")).isEqualTo(ADMIN_API_KEY);

        // mesmo banco nos dois contextos
        MongoTemplate workerMongo = worker.getBean(MongoTemplate.class);
        MongoTemplate apiMongo = api.getBean(MongoTemplate.class);
        assertThat(workerMongo.getDb().getName()).isEqualTo(apiMongo.getDb().getName());

        // o worker semeou os templates no banco compartilhado
        assertThat(workerMongo.exists(new Query(Criteria.where("_id").is("welcome")), Document.class,
                Collections.TEMPLATES)).isTrue();

        // só o profile it, nunca o da demo
        assertThat(workerEnv.getActiveProfiles()).containsExactly("it");
        assertThat(apiEnv.getActiveProfiles()).containsExactly("it");
    }
}
