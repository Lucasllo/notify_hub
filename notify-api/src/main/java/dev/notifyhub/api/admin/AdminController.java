package dev.notifyhub.api.admin;

import dev.notifyhub.api.admin.DlqReprocessService.Report;
import dev.notifyhub.common.domain.Channel;
import java.util.Arrays;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints administrativos. {@code POST /admin/dlq/{canal}/reprocess?max=N} move até N mensagens da
 * DLQ do canal de volta à fila principal (ADM-01) e responde com as contagens, ou 503 com as
 * contagens parciais na primeira falha (D-03).
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final DlqReprocessService reprocessService;
    private final AdminProperties props;

    public AdminController(DlqReprocessService reprocessService, AdminProperties props) {
        this.reprocessService = reprocessService;
        this.props = props;
    }

    @PostMapping("/dlq/{canal}/reprocess")
    public Report reprocess(@PathVariable("canal") String canal,
                            @RequestParam(name = "max", required = false) Integer max) {
        Channel channel = Arrays.stream(Channel.values())
                .filter(c -> c.id().equals(canal))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("canal desconhecido"));
        return reprocessService.reprocess(channel, max != null ? max : props.maxDefault());
    }
}
