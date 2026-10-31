package dev.notifyhub.api.web;

import dev.notifyhub.api.application.NotificationQueryService;
import dev.notifyhub.api.web.ApiExceptionHandler.FieldProblem;
import dev.notifyhub.api.web.dto.NotificationStats;
import dev.notifyhub.api.web.dto.NotificationSummary;
import dev.notifyhub.api.web.dto.PageResponse;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Leitura operacional das notificações: {@code GET /notifications} lista com filtros e paginação
 * (API-05, D-09..D-11), e {@code GET /notifications/stats} traz as contagens por status e canal
 * (API-07, D-12).
 *
 * <p>É um controller separado do {@link NotificationController}, que cuida da criação e do GET por id.
 * Os dois dividem o prefixo {@code /notifications}. As rotas literais deste controller (como
 * {@code /notifications/stats}) têm precedência sobre o padrão {@code /notifications/{id}} no
 * {@code PathPattern} do Spring MVC, então nunca caem no GET por id.
 *
 * <p>Os parâmetros chegam como {@code String}/{@code Integer} opcionais e são validados aqui, todos de
 * uma vez: qualquer violação vira um único 400 {@code validation-error} com um item por parâmetro em
 * {@code errors[]}, sem consultar o Mongo. Nada é limitado em silêncio (D-10). Um {@code page} ou
 * {@code size} que não converte para inteiro ({@code page=abc}) passa pelo
 * {@code ApiExceptionHandler.handleTypeMismatch}, com o mesmo formato.
 */
@RestController
@RequestMapping("/notifications")
@Tag(name = "Consultas", description = "Listagem paginada e estatísticas")
public class NotificationQueryController {

    /** Tamanho de página quando {@code size} não vem. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Maior {@code size} aceito; acima disso, 400. */
    public static final int MAX_PAGE_SIZE = 100;

    private static final String ACCEPTED_STATUSES = acceptedValues(NotificationStatus.values());
    private static final String ACCEPTED_CHANNELS = acceptedValues(Channel.values());

    private final NotificationQueryService service;

    public NotificationQueryController(NotificationQueryService service) {
        this.service = service;
    }

    /**
     * Lista as notificações, mais recentes primeiro, no envelope
     * {@code {content, page, size, totalElements, totalPages}}.
     *
     * @param status  status exato (nome do enum em maiúsculo); vazio ou ausente = sem filtro
     * @param channel canal exato (EMAIL, SMS, WEBHOOK); vazio ou ausente = sem filtro
     * @param page    página a partir de 0 (padrão 0)
     * @param size    itens por página, de 1 a {@value #MAX_PAGE_SIZE} (padrão {@value #DEFAULT_PAGE_SIZE})
     */
    @Operation(summary = "Lista as notificações com filtros e paginação",
            description = """
                    Mais recentes primeiro (createdAt desc), no envelope {content, page, size, totalElements,                     totalPages}. Cada item é um resumo, sem variáveis, conteúdo renderizado nem histórico                     (use o GET por id para isso). Parâmetros inválidos saem todos num único 400.""")
    @ApiResponse(responseCode = "200", description = "Página de resumos (vazia além da última página)")
    @ApiResponse(responseCode = "400", description = "status, channel, page ou size inválido (validation-error)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public PageResponse<NotificationSummary> list(
            @Parameter(description = "Status exato, em maiúsculo: PENDING, PROCESSING, SENT, RETRYING, FAILED, "
                    + "DEAD_LETTERED ou PUBLISH_FAILED; vazio ou ausente = sem filtro")
            @RequestParam(required = false) String status,
            @Parameter(description = "Canal exato, em maiúsculo: EMAIL, SMS ou WEBHOOK; vazio ou ausente = sem filtro")
            @RequestParam(required = false) String channel,
            @Parameter(description = "Página a partir de 0 (padrão 0); além da última, a página vem vazia")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Itens por página, de 1 a 100 (padrão 20); fora disso, 400")
            @RequestParam(required = false) Integer size) {
        List<FieldProblem> errors = new ArrayList<>();
        NotificationStatus statusFilter = parseEnum(NotificationStatus.class, status, "status", ACCEPTED_STATUSES,
                errors);
        Channel channelFilter = parseEnum(Channel.class, channel, "channel", ACCEPTED_CHANNELS, errors);
        int resolvedPage = page == null ? 0 : page;
        if (resolvedPage < 0) {
            errors.add(new FieldProblem("page", "deve ser maior ou igual a 0"));
        }
        int resolvedSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (resolvedSize < 1 || resolvedSize > MAX_PAGE_SIZE) {
            errors.add(new FieldProblem("size", "deve estar entre 1 e " + MAX_PAGE_SIZE));
        }
        if (!errors.isEmpty()) {
            throw ApiExceptionHandler.invalidRequest(errors);
        }
        return service.list(statusFilter, channelFilter, resolvedPage, resolvedSize);
    }

    /**
     * Contagens {@code {total, byStatus, byChannel, byChannelAndStatus}}, com os 7 status e os 3 canais
     * sempre presentes, inclusive com zero. Rota literal: tem precedência sobre
     * {@code GET /notifications/{id}}, então nunca responde 404 de notificação.
     */
    @Operation(summary = "Estatísticas por status e canal",
            description = """
                    Contagens {total, byStatus, byChannel, byChannelAndStatus} numa única agregação. Os 7                     status, os 3 canais e as 21 combinações aparecem sempre, inclusive com zero.""")
    @ApiResponse(responseCode = "200", description = "Contagens atuais")
    @GetMapping("/stats")
    public NotificationStats stats() {
        return service.stats();
    }

    /**
     * Nome exato do enum, como no POST: minúsculo é inválido. Vazio ou em branco = sem filtro (nulo).
     * Valor inválido acumula um erro com os valores aceitos e devolve nulo.
     */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field, String accepted,
            List<FieldProblem> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            errors.add(new FieldProblem(field, accepted));
            return null;
        }
    }

    private static String acceptedValues(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.joining(", ", "valores aceitos: ", ""));
    }
}
