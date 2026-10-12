package dev.notifyhub.api.web.dto;

import dev.notifyhub.common.domain.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Corpo do {@code POST /notifications} (D-01): campos planos no nível raiz. As regras que dependem
 * do canal (formato do destinatário) e as regras de {@code variables} ficam no
 * {@code CreateNotificationRequestValidator}.
 */
public record CreateNotificationRequest(
        @NotNull(message = "é obrigatório; valores aceitos: EMAIL, SMS, WEBHOOK")
        Channel channel,

        @NotBlank(message = "é obrigatório")
        @Size(max = 2048, message = "deve ter no máximo 2048 caracteres")
        String recipient,

        @NotBlank(message = "é obrigatório")
        @Size(max = 100, message = "deve ter no máximo 100 caracteres")
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,99}$",
                message = "deve conter só letras minúsculas, dígitos e hífen, começando por letra ou dígito")
        String templateCode,

        @NotNull(message = "é obrigatório (use {} quando o template não tiver variáveis)")
        Map<String, Object> variables) {
}
