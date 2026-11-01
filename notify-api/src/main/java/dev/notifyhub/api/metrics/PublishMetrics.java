package dev.notifyhub.api.metrics;

import dev.notifyhub.api.messaging.PublishResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Contador do resultado do publish da API (D-02, OBS-02), contrato do dashboard provisionado e do
 * README da Fase 4. É o par do {@code DeliveryMetrics} do worker (D-15 da Fase 2).
 *
 * <p>{@value #OUTCOMES}{@code {outcome, reason}} (no Prometheus, {@code notify_publish_outcomes_total})
 * tem <b>7 séries pré-registradas no construtor</b>, com valor 0, para o Prometheus e o Grafana verem a
 * série antes do primeiro publish:
 * <ul>
 *   <li>{@code confirmed/none}: o broker confirmou (ack);</li>
 *   <li>{@code confirmed/claimed}: sem confirm, mas a releitura mostra o claim do worker, ou seja, a
 *       mensagem chegou;</li>
 *   <li>{@code publish_failed/nack}, {@code publish_failed/returned}, {@code publish_failed/timeout} e
 *       {@code publish_failed/broker_unavailable}: sem confirm, com o motivo vindo de
 *       {@link PublishResult.Outcome};</li>
 *   <li>{@code publish_failed/unconfirmed}: PENDING sem confirm levado a PUBLISH_FAILED pela varredura,
 *       uma unidade por documento marcado.</li>
 * </ul>
 *
 * <p>Todas as séries têm as mesmas chaves {@code outcome} e {@code reason} (por isso o ack leva
 * {@code reason="none"}): o registry Prometheus descarta meters de mesmo nome com chaves diferentes. Os
 * valores são de conjunto fechado. Id da notificação, destinatário, template e texto do broker (reply
 * text, motivo do nack, mensagem de exceção) nunca viram tag: ficam no Mongo e no log (T-04-07, T-04-08).
 *
 * <p>Quem conta: {@code NotificationRepublisher.publishOrMarkFailed}, que atende a criação, o replay com a
 * mesma {@code Idempotency-Key} e o republish, e a varredura de PENDING sem confirm
 * ({@code NotificationRepublisher.markUnconfirmedAsPublishFailed}). Quem não conta: o reprocessamento da
 * DLQ e a cópia para o fim da DLQ, que chamam o {@code NotificationPublisher} direto, porque essas falhas
 * não gravam PUBLISH_FAILED.
 */
@Component
public class PublishMetrics {

    public static final String OUTCOMES = "notify.publish.outcomes";

    private static final String OUTCOME_CONFIRMED = "confirmed";
    private static final String OUTCOME_PUBLISH_FAILED = "publish_failed";

    private final Counter confirmed;
    private final Counter confirmedByClaim;
    private final Counter unconfirmed;
    private final Map<PublishResult.Outcome, Counter> failures = new EnumMap<>(PublishResult.Outcome.class);

    public PublishMetrics(MeterRegistry registry) {
        this.confirmed = counter(registry, OUTCOME_CONFIRMED, "none");
        this.confirmedByClaim = counter(registry, OUTCOME_CONFIRMED, "claimed");
        for (PublishResult.Outcome outcome : PublishResult.Outcome.values()) {
            if (outcome != PublishResult.Outcome.ACK) {
                failures.put(outcome, counter(registry, OUTCOME_PUBLISH_FAILED, reason(outcome)));
            }
        }
        this.unconfirmed = counter(registry, OUTCOME_PUBLISH_FAILED, "unconfirmed");
    }

    private static Counter counter(MeterRegistry registry, String outcome, String reason) {
        return Counter.builder(OUTCOMES)
                .description("Resultado do publish da API: confirm do broker ou PUBLISH_FAILED, por motivo")
                .tag("outcome", outcome)
                .tag("reason", reason)
                .register(registry);
    }

    /**
     * Valor da tag {@code reason} de um publish sem confirm.
     *
     * @throws IllegalArgumentException para ACK, que nunca é falha
     */
    private static String reason(PublishResult.Outcome outcome) {
        return switch (outcome) {
            case NACK -> "nack";
            case RETURNED -> "returned";
            case TIMEOUT -> "timeout";
            case BROKER_UNAVAILABLE -> "broker_unavailable";
            case ACK -> throw new IllegalArgumentException("ACK nunca é falha de publish");
        };
    }

    /** Chamado quando o broker confirmou o publish (ack). */
    public void confirmed() {
        confirmed.increment();
    }

    /**
     * Chamado quando o publish ficou sem confirm, o update PENDING → PUBLISH_FAILED casou 0 documentos e a
     * releitura mostra o claim do worker (a mensagem chegou).
     */
    public void confirmedByClaim() {
        confirmedByClaim.increment();
    }

    /**
     * Chamado quando o publish ficou sem confirm e foi tratado como falha (PUBLISH_FAILED gravado, ou sem
     * evidência de claim do worker).
     *
     * @param outcome o desfecho do publish; vira a tag {@code reason}
     * @throws IllegalArgumentException se {@code outcome} for ACK
     */
    public void failed(PublishResult.Outcome outcome) {
        Counter counter = failures.get(outcome);
        if (counter == null) {
            // só ACK fica fora do mapa: o switch exaustivo de reason() cobre todo outro desfecho
            throw new IllegalArgumentException("ACK nunca é falha de publish");
        }
        counter.increment();
    }

    /**
     * Chamado pela varredura de PENDING sem confirm com o número de documentos levados a PUBLISH_FAILED.
     *
     * @param count documentos marcados; 0 não muda nada
     * @throws IllegalArgumentException se {@code count} for negativo
     */
    public void unconfirmed(long count) {
        if (count < 0) {
            throw new IllegalArgumentException("contagem negativa de documentos marcados: " + count);
        }
        if (count > 0) {
            unconfirmed.increment(count);
        }
    }
}
