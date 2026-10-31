package dev.notifyhub.api.admin;

import dev.notifyhub.api.admin.DlqReprocessService.Report;
import dev.notifyhub.api.application.NotificationRepublisher;
import dev.notifyhub.api.web.ApiExceptionHandler;
import dev.notifyhub.api.web.ApiExceptionHandler.FieldProblem;
import dev.notifyhub.api.web.OpenApiConfiguration;
import dev.notifyhub.common.domain.Channel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints administrativos, protegidos pelo header {@code X-API-Key} (ADM-04, interceptor do
 * {@link AdminWebConfiguration}).
 * <ul>
 *   <li>{@code POST /admin/dlq/{canal}/reprocess?max=N} move até N mensagens da DLQ do canal de volta
 *       à fila principal (ADM-01) e responde com as contagens, ou 503 com as contagens parciais na
 *       primeira falha (D-03).</li>
 *   <li>{@code POST /admin/notifications/republish-failed?max=N} republica até N notificações
 *       PUBLISH_FAILED, da mais antiga para a mais nova, e responde {@code {requested, republished}}
 *       (ADM-03). Usa o mesmo serviço do replay de PUBLISH_FAILED (D-08).</li>
 * </ul>
 * Nos dois, {@code max} vai de 1 a {@code notify.admin.max-limit}, com padrão
 * {@code notify.admin.max-default} (D-04).
 *
 * <p>O {@code @SecurityRequirement} só documenta a chave no OpenAPI (API-08); quem protege de fato
 * continua sendo o interceptor.
 */
@RestController
@RequestMapping("/admin")
@SecurityRequirement(name = OpenApiConfiguration.ADMIN_API_KEY_SCHEME)
@Tag(name = "Administração",
        description = "Reprocessamento da DLQ e republicação de PUBLISH_FAILED; exige X-API-Key")
public class AdminController {

    private static final String MAX_DESCRIPTION = "Máximo de itens: padrão 100, de 1 a 1000";

    private static final String ACCEPTED_CHANNEL_IDS = Arrays.stream(Channel.values())
            .map(Channel::id)
            .collect(Collectors.joining(", ", "valores aceitos: ", ""));

    private final DlqReprocessService reprocessService;
    private final NotificationRepublisher republisher;
    private final AdminProperties props;

    public AdminController(DlqReprocessService reprocessService, NotificationRepublisher republisher,
                           AdminProperties props) {
        this.reprocessService = reprocessService;
        this.republisher = republisher;
        this.props = props;
    }

    /**
     * Reprocessa a DLQ de um canal.
     *
     * @param canal id do canal em minúsculo ({@code email}, {@code sms} ou {@code webhook})
     * @param max   máximo de mensagens, de 1 a {@code notify.admin.max-limit}; ausente usa
     *              {@code notify.admin.max-default} (D-04)
     */
    @Operation(summary = "Reprocessa a DLQ de um canal",
            description = """
                    Move até min(max, mensagens prontas na DLQ) mensagens de volta à fila principal do canal,                     zerando o contador de retries de cada notificação. Responde {requested, reprocessed,                     skipped, discarded}: skipped volta para o fim da DLQ (notificação em entrega agora),                     discarded sai da DLQ (notificação já SENT ou inexistente). Para na primeira falha com                     503 e as contagens parciais.""")
    @ApiResponse(responseCode = "200", description = "Contagens do reprocessamento",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = Report.class)))
    @ApiResponse(responseCode = "400", description = "canal ou max inválido (validation-error)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "X-API-Key ausente ou errada (unauthorized)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503",
            description = "Interrompido na primeira falha (reprocess-interrupted), com as contagens parciais",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/dlq/{canal}/reprocess")
    public Report reprocess(@Parameter(description = "email, sms ou webhook") @PathVariable("canal") String canal,
                            @Parameter(description = MAX_DESCRIPTION)
                            @RequestParam(name = "max", required = false) Integer max) {
        // Validação antes de tocar na DLQ; os dois erros saem num único 400
        List<FieldProblem> errors = new ArrayList<>();
        Optional<Channel> channel = Arrays.stream(Channel.values())
                .filter(c -> c.id().equals(canal))
                .findFirst();
        if (channel.isEmpty()) {
            errors.add(new FieldProblem("canal", ACCEPTED_CHANNEL_IDS));
        }
        int effectiveMax = resolveMax(max, errors);
        if (!errors.isEmpty()) {
            throw ApiExceptionHandler.invalidRequest(errors);
        }
        return reprocessService.reprocess(channel.orElseThrow(), effectiveMax);
    }

    /**
     * Republica as notificações PUBLISH_FAILED (ADM-03).
     *
     * @param max máximo de notificações, de 1 a {@code notify.admin.max-limit}; ausente usa
     *            {@code notify.admin.max-default} (D-04)
     */
    @Operation(summary = "Republica as notificações PUBLISH_FAILED",
            description = """
                    Republica até max notificações PUBLISH_FAILED, da mais antiga para a mais nova (createdAt),                     e responde {requested, republished}. Para na primeira publicação não confirmada com 503                     e as contagens parciais.""")
    @ApiResponse(responseCode = "200", description = "Contagens da republicação",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = NotificationRepublisher.RepublishReport.class)))
    @ApiResponse(responseCode = "400", description = "max inválido (validation-error)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "X-API-Key ausente ou errada (unauthorized)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503",
            description = "Interrompido na primeira falha (republish-interrupted), com as contagens parciais",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/notifications/republish-failed")
    public NotificationRepublisher.RepublishReport republishFailed(
            @Parameter(description = MAX_DESCRIPTION)
            @RequestParam(name = "max", required = false) Integer max) {
        List<FieldProblem> errors = new ArrayList<>();
        int effectiveMax = resolveMax(max, errors);
        if (!errors.isEmpty()) {
            throw ApiExceptionHandler.invalidRequest(errors);
        }
        return republisher.republishFailed(effectiveMax);
    }

    /**
     * {@code max} efetivo: o padrão quando ausente; fora de 1..{@code maxLimit} acumula um
     * {@link FieldProblem} em {@code errors}.
     */
    private int resolveMax(Integer max, List<FieldProblem> errors) {
        int effectiveMax = max != null ? max : props.maxDefault();
        if (effectiveMax < 1 || effectiveMax > props.maxLimit()) {
            errors.add(new FieldProblem("max", "deve estar entre 1 e " + props.maxLimit()));
        }
        return effectiveMax;
    }
}
