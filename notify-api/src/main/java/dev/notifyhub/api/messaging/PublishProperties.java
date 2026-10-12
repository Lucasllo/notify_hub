package dev.notifyhub.api.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propriedades de publicação da API (D-04).
 *
 * @param confirmTimeout quanto tempo esperar o publisher confirm ({@code notify.publish.confirm-timeout})
 * @param retryAfter     valor do header {@code Retry-After} no 503 ({@code notify.publish.retry-after})
 */
@ConfigurationProperties("notify.publish")
public record PublishProperties(
        @DefaultValue("5s") Duration confirmTimeout,
        @DefaultValue("5s") Duration retryAfter) {
}
