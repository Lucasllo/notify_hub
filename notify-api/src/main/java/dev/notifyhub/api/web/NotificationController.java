package dev.notifyhub.api.web;

import dev.notifyhub.api.application.NotificationService;
import dev.notifyhub.api.web.ApiExceptionHandler.FieldProblem;
import dev.notifyhub.api.web.dto.CreateNotificationRequest;
import dev.notifyhub.api.web.dto.CreateNotificationResponse;
import dev.notifyhub.api.web.dto.NotificationView;
import dev.notifyhub.api.web.validation.CreateNotificationRequestValidator;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /notifications} (202 + Location, D-02; Idempotency-Key, D-07) e
 * {@code GET /notifications/{id}} (D-03).
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    /** Header opcional com a chave de idempotência do cliente (API-03). */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** Header {@code true} no 202 que repete uma notificação já criada com a mesma chave (D-07). */
    public static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

    /**
     * Formato aceito da chave: de 1 a 255 caracteres ASCII visíveis, sem espaço nem bytes de controle
     * (T-03-19).
     */
    private static final Pattern IDEMPOTENCY_KEY_FORMAT = Pattern.compile("^[\\x21-\\x7E]{1,255}$");

    private static final CreateNotificationRequestValidator REQUEST_VALIDATOR = new CreateNotificationRequestValidator();

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /** Soma o validador por canal às constraints do {@code @Valid}: um único 400 com todos os erros. */
    @InitBinder("createNotificationRequest")
    void registerRequestValidator(WebDataBinder binder) {
        binder.addValidators(REQUEST_VALIDATOR);
    }

    /**
     * Cria a notificação: 202 com {@code Location} e {@code {id, status, createdAt}} (D-02).
     *
     * <p>Com o header {@value #IDEMPOTENCY_KEY_HEADER}, repetir o POST com o mesmo payload devolve o
     * mesmo 202 da notificação original, com o status atual e o header
     * {@code Idempotent-Replayed: true}, sem criar outro documento nem publicar de novo (D-07). O primeiro
     * POST não leva esse header.
     *
     * <p>A chave precisa ter de 1 a 255 caracteres ASCII visíveis; fora disso, 400 de validação com o
     * campo {@code Idempotency-Key}, sem gravar nada. A validação do corpo ({@code @Valid} e o validador
     * por canal) roda antes, no binding, então um corpo inválido continua dando o 400 do corpo. Use
     * chaves de alta entropia (UUID): a API pública não tem autenticação, e quem adivinha uma chave
     * recebe o replay da notificação dela.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreateNotificationResponse> create(@Valid @RequestBody CreateNotificationRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        if (idempotencyKey != null && !IDEMPOTENCY_KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw ApiExceptionHandler.invalidRequest(List.of(new FieldProblem(IDEMPOTENCY_KEY_HEADER,
                    "deve ter de 1 a 255 caracteres ASCII visíveis, sem espaço")));
        }
        NotificationService.CreateResult result = service.create(request, idempotencyKey);
        CreateNotificationResponse response = result.response();
        ResponseEntity.BodyBuilder accepted = ResponseEntity.accepted()
                .location(URI.create("/notifications/" + response.id()));
        if (result.replayed()) {
            accepted.header(IDEMPOTENT_REPLAYED_HEADER, "true");
        }
        return accepted.body(response);
    }

    @GetMapping("/{id}")
    public NotificationView get(@PathVariable String id) {
        return service.get(id);
    }
}
