package dev.notifyhub.api.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propriedades dos endpoints administrativos (ADM-04, D-04).
 *
 * @param apiKey     chave exigida no header {@code X-API-Key} ({@code notify.admin.api-key}); vazia ou
 *                   ausente recusa toda chamada a {@code /admin/**} (fail closed)
 * @param maxDefault {@code max} usado quando o parâmetro não vem na chamada
 *                   ({@code notify.admin.max-default})
 * @param maxLimit   maior {@code max} aceito ({@code notify.admin.max-limit})
 */
@ConfigurationProperties("notify.admin")
public record AdminProperties(
        String apiKey,
        @DefaultValue("100") int maxDefault,
        @DefaultValue("1000") int maxLimit) {

    public AdminProperties {
        if (maxLimit < 1) {
            throw new IllegalArgumentException("notify.admin.max-limit deve ser pelo menos 1 (recebido: " + maxLimit + ")");
        }
        if (maxDefault < 1 || maxDefault > maxLimit) {
            throw new IllegalArgumentException("notify.admin.max-default deve estar entre 1 e notify.admin.max-limit ("
                    + maxLimit + "); recebido: " + maxDefault);
        }
    }
}
