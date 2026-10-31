package dev.notifyhub.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.it.support.EndToEndSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Saída do PENDING que nunca teve confirm do broker (Gap 1 do 03-VERIFICATION, CR-01 do 03-REVIEW),
 * contra RabbitMQ e MongoDB reais. Um documento PENDING sem claim e sem mensagem em fila (o estado que
 * fica quando a JVM cai entre o insert e o confirm, ou quando o MongoDB falha no
 * {@code markPublishFailed}) é inserido direto no Mongo, com {@code updatedAt} de 2h atrás.
 *
 * <p>A varredura agendada da API ({@code notify.publish.unconfirmed-sweep}, no profile {@code it}:
 * {@code after} 1h e {@code interval} 200ms) o leva a PUBLISH_FAILED com {@code lastError.code}
 * PUBLISH_UNCONFIRMED, e o {@code POST /admin/notifications/republish-failed} do operador o republica
 * até SENT (ADM-03). Um PENDING recente, avaliado pela mesma varredura, continua PENDING.
 *
 * <p>Toda espera é com Awaitility (D-16), nunca pausa fixa.
 */
class UnconfirmedPublishRecoveryIT extends EndToEndSupport {

    private static final String REPUBLISH_PATH = "/admin/notifications/republish-failed";
    private static final Duration SWEEP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SENT_TIMEOUT = Duration.ofSeconds(15);

    @Test
    void unconfirmedPendingIsSweptToPublishFailedAndRepublishedToSent() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String orphan = insertPending(now.minus(Duration.ofHours(2)));
        String control = insertPending(now);

        HttpResult swept = await("PENDING órfão " + orphan + " varrido para PUBLISH_FAILED")
                .atMost(SWEEP_TIMEOUT)
                .pollInterval(Duration.ofMillis(50))
                .until(() -> get("/notifications/" + orphan),
                        r -> r.status() == 200 && r.body() != null
                                && "PUBLISH_FAILED".equals(r.body().path("status").asText()));
        assertThat(swept.body().path("lastError").path("code").asText()).isEqualTo("PUBLISH_UNCONFIRMED");
        assertThat(swept.body().path("lastError").path("message").asText())
                .isEqualTo("Publicação não confirmada pelo broker (PUBLISH_UNCONFIRMED)");

        // A mesma varredura avaliou os dois: o PENDING recente está dentro do corte e fica intacto
        HttpResult controlView = get("/notifications/" + control);
        assertThat(controlView.status()).isEqualTo(200);
        assertThat(controlView.body().path("status").asText()).isEqualTo("PENDING");

        HttpResult republished = adminPost(REPUBLISH_PATH);
        assertThat(republished.status()).as(republished.raw()).isEqualTo(200);
        assertThat(republished.body().path("requested").asInt()).isEqualTo(100);
        assertThat(republished.body().path("republished").asInt()).isEqualTo(1);

        awaitStatus(orphan, NotificationStatus.SENT, SENT_TIMEOUT);
    }

    /**
     * Insere direto no Mongo, sem publicar mensagem, um PENDING de e-mail para {@code ana@exemplo.com}
     * (template {@code welcome}, {@code name: Ana}) com {@code createdAt} = {@code updatedAt} = o instante
     * pedido e sem {@code claimedAt}.
     */
    private static String insertPending(Instant at) {
        NotificationDocument doc = new NotificationDocument(UUID.randomUUID().toString(), Channel.EMAIL,
                "ana@exemplo.com", "welcome", Map.of("name", "Ana"), NotificationStatus.PENDING, 0, 0, null, null,
                List.of(), at, at, null, null, null);
        return mongo().insert(doc).id();
    }
}
