package dev.notifyhub.api.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propriedades da varredura de PENDING sem confirm ({@link UnconfirmedPublishSweeper}).
 *
 * @param after    idade mínima de {@code updatedAt} para um PENDING sem claim ser considerado sem
 *                 confirm do broker ({@code notify.publish.unconfirmed-sweep.after}). Precisa ser
 *                 ≥ 3× {@code notify.publish.confirm-timeout}, para a varredura nunca alcançar uma
 *                 publicação ainda em voo nem a janela do replay
 * @param interval atraso entre o fim de uma varredura e o início da próxima
 *                 ({@code notify.publish.unconfirmed-sweep.interval})
 */
@ConfigurationProperties("notify.publish.unconfirmed-sweep")
public record UnconfirmedSweepProperties(
        @DefaultValue("2m") Duration after,
        @DefaultValue("30s") Duration interval) {
}
