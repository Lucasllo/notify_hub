package dev.notifyhub.api.admin;

import dev.notifyhub.api.admin.DlqReprocessService.Report;
import dev.notifyhub.api.web.ApiExceptionHandler;
import dev.notifyhub.api.web.ApiExceptionHandler.FieldProblem;
import dev.notifyhub.common.domain.Channel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints administrativos, protegidos pelo header {@code X-API-Key} (ADM-04, interceptor do
 * {@link AdminWebConfiguration}). {@code POST /admin/dlq/{canal}/reprocess?max=N} move até N
 * mensagens da DLQ do canal de volta à fila principal (ADM-01) e responde com as contagens, ou 503
 * com as contagens parciais na primeira falha (D-03).
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final String ACCEPTED_CHANNEL_IDS = Arrays.stream(Channel.values())
            .map(Channel::id)
            .collect(Collectors.joining(", ", "valores aceitos: ", ""));

    private final DlqReprocessService reprocessService;
    private final AdminProperties props;

    public AdminController(DlqReprocessService reprocessService, AdminProperties props) {
        this.reprocessService = reprocessService;
        this.props = props;
    }

    /**
     * Reprocessa a DLQ de um canal.
     *
     * @param canal id do canal em minúsculo ({@code email}, {@code sms} ou {@code webhook})
     * @param max   máximo de mensagens, de 1 a {@code notify.admin.max-limit}; ausente usa
     *              {@code notify.admin.max-default} (D-04)
     */
    @PostMapping("/dlq/{canal}/reprocess")
    public Report reprocess(@PathVariable("canal") String canal,
                            @RequestParam(name = "max", required = false) Integer max) {
        // Validação antes de tocar na DLQ; os dois erros saem num único 400
        List<FieldProblem> errors = new ArrayList<>();
        Optional<Channel> channel = Arrays.stream(Channel.values())
                .filter(c -> c.id().equals(canal))
                .findFirst();
        if (channel.isEmpty()) {
            errors.add(new FieldProblem("canal", ACCEPTED_CHANNEL_IDS));
        }
        int effectiveMax = max != null ? max : props.maxDefault();
        if (effectiveMax < 1 || effectiveMax > props.maxLimit()) {
            errors.add(new FieldProblem("max", "deve estar entre 1 e " + props.maxLimit()));
        }
        if (!errors.isEmpty()) {
            throw ApiExceptionHandler.invalidRequest(errors);
        }
        return reprocessService.reprocess(channel.orElseThrow(), effectiveMax);
    }
}
