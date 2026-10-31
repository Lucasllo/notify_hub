package dev.notifyhub.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.messaging.NotifyHeaders;
import dev.notifyhub.common.messaging.NotifyTopology;
import dev.notifyhub.common.persistence.Collections;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.it.support.EndToEndSupport;
import dev.notifyhub.worker.metrics.DeliveryMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Garantias de entrega com broker real (TST-03, critério 4 do ROADMAP): {@code ./mvnw verify} prova
 * que uma mensagem venenosa estaciona na DLQ uma vez, sem loop; que uma falha permanente vai direto
 * para FAILED sem gastar níveis de retry; e que cópias duplicadas concorrentes da mesma mensagem
 * chamam o adapter exatamente uma vez.
 *
 * <p><b>Prova de "exatamente um envio".</b> O timer {@value DeliveryMetrics#DURATION} é gravado só em
 * volta da chamada ao adapter (por dentro de bulkhead e circuit breaker), então o delta da série
 * {@code outcome=success} conta envios reais. O contador {@value DeliveryMetrics#OUTCOMES}
 * {@code {outcome=sent}} conta as escritas de SENT que ganharam o fence. Os dois ficam em 1 por 500ms.
 *
 * <p>A publicação de não roteável (terceira parte do TST-03) fica no plano 03-05, junto com o
 * republish. Toda espera é com Awaitility.
 */
class DeliveryGuaranteesIT extends EndToEndSupport {

    private static final String MAIN_QUEUE = "notify.email";
    private static final String DLQ = "notify.email.dlq";
    private static final List<String> DELAY_QUEUES = List.of(
            "notify.email.retry.200ms", "notify.email.retry.500ms", "notify.email.retry.1s");

    @Test
    void poisonMessageGoesToTheDlqOnceWithoutLooping() {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit().send(NotifyTopology.MAIN_EXCHANGE, Channel.EMAIL.routingKey(),
                new Message("isto não é json".getBytes(StandardCharsets.UTF_8), props));

        await("venenosa na DLQ e fila principal vazia")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> readyCount(DLQ) == 1 && readyCount(MAIN_QUEUE) == 0);
        // Sem loop: o rejeite sem requeue não devolve a mensagem à fila principal
        await("contagens estáveis, sem loop")
                .during(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(3))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> readyCount(DLQ) == 1 && readyCount(MAIN_QUEUE) == 0);

        // A limpeza do próximo teste purga a DLQ de qualquer jeito
        Message dead = rabbit().receive(DLQ, 2000);
        assertThat(dead).as("mensagem na DLQ").isNotNull();
        List<Map<String, ?>> xDeath = xDeath(dead.getMessageProperties());
        assertThat(xDeath).as("x-death").isNotEmpty();
        Map<String, ?> first = xDeath.get(0);
        assertThat(((Number) first.get("count")).longValue()).isEqualTo(1L);
        assertThat(String.valueOf(first.get("reason"))).isEqualTo("rejected");
        assertThat(String.valueOf(first.get("queue"))).isEqualTo(MAIN_QUEUE);
    }

    @Test
    void alwaysPermanentEndsFailedWithAPermanentCopyAndNoRetry() {
        HttpResult created = post("/notifications", """
                {"channel":"EMAIL","recipient":"always-permanent@fail.test","templateCode":"welcome","variables":{"name":"Ana"}}
                """);
        assertThat(created.status()).as(created.raw()).isEqualTo(202);
        String id = created.body().path("id").asText();

        JsonNode failed = awaitStatus(id, NotificationStatus.FAILED, Duration.ofSeconds(10));
        JsonNode attempts = failed.path("attempts");
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).path("outcome").asText()).isEqualTo("PERMANENT_FAILURE");
        assertThat(rawDocument(id).getInteger("attemptCount")).isEqualTo(1);

        await("cópia permanente na DLQ")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> readyCount(DLQ) == 1);
        // Nenhum nível de retry é gasto: as três filas de delay ficam vazias
        await("filas de delay vazias e DLQ com 1")
                .during(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(3))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> readyCount(DLQ) == 1 && DELAY_QUEUES.stream().allMatch(q -> readyCount(q) == 0));

        Message copy = rabbit().receive(DLQ, 2000);
        assertThat(copy).as("cópia na DLQ").isNotNull();
        assertThat(String.valueOf((Object) copy.getMessageProperties().getHeader(NotifyHeaders.FAILURE_CLASS)))
                .isEqualTo("PERMANENT");
    }

    @Test
    void concurrentDuplicateDeliveryCallsTheAdapterExactlyOnce() {
        MeterRegistry registry = worker().getBean(MeterRegistry.class);
        double sendsBefore = successTimer(registry).count();
        double sentBefore = sentCounter(registry).count();

        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        mongo().insert(NotificationDocument.newPending(id, Channel.EMAIL, "ana@exemplo.com", "welcome",
                Map.of("name", "Ana"), now));

        // 5 cópias da mesma mensagem: os 10 consumidores (prefetch 10) as disputam em paralelo
        NotificationMessage message = new NotificationMessage(id, Channel.EMAIL, "welcome", now);
        for (int i = 0; i < 5; i++) {
            rabbit().convertAndSend(NotifyTopology.MAIN_EXCHANGE, Channel.EMAIL.routingKey(), message, m -> {
                m.getMessageProperties().setMessageId(id);
                m.getMessageProperties().setHeader(NotifyHeaders.RETRY_COUNT, 0);
                return m;
            });
        }

        JsonNode sent = awaitStatus(id, NotificationStatus.SENT, Duration.ofSeconds(10));
        assertThat(rawDocument(id).getInteger("attemptCount")).isEqualTo(1);
        JsonNode attempts = sent.path("attempts");
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).path("outcome").asText()).isEqualTo("SUCCESS");

        // Exatamente um envio real e uma escrita de SENT, estáveis enquanto as cópias são descartadas
        await("um envio e uma escrita de SENT, fila principal vazia")
                .during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> successTimer(registry).count() - sendsBefore == 1
                        && sentCounter(registry).count() - sentBefore == 1
                        && readyCount(MAIN_QUEUE) == 0);
        assertThat(successTimer(registry).count() - sendsBefore).isEqualTo(1);
        assertThat(sentCounter(registry).count() - sentBefore).isEqualTo(1.0);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static RabbitTemplate rabbit() {
        return api().getBean(RabbitTemplate.class);
    }

    /** Timer do adapter: envios reais com sucesso no canal de e-mail. */
    private static Timer successTimer(MeterRegistry registry) {
        return registry.get(DeliveryMetrics.DURATION).tags("channel", "email", "outcome", "success").timer();
    }

    /** Escritas de SENT que ganharam o fence no canal de e-mail. */
    private static Counter sentCounter(MeterRegistry registry) {
        return registry.get(DeliveryMetrics.OUTCOMES)
                .tags("channel", "email", "outcome", "sent", "failure_class", "none").counter();
    }

    /** {@code x-death} pelo acessor do Spring AMQP, ou pelo header cru se o conversor o mantiver lá. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, ?>> xDeath(MessageProperties props) {
        List<Map<String, ?>> header = props.getXDeathHeader();
        if (header == null) {
            header = (List<Map<String, ?>>) props.getHeaders().get("x-death");
        }
        return header;
    }

    private static Document rawDocument(String id) {
        Document doc = mongo().findById(id, Document.class, Collections.NOTIFICATIONS);
        assertThat(doc).as("documento " + id).isNotNull();
        return doc;
    }
}
