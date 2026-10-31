package dev.notifyhub.api.web;

import dev.notifyhub.api.application.NotificationService;
import dev.notifyhub.api.web.dto.CreateNotificationRequest;
import dev.notifyhub.api.web.dto.CreateNotificationResponse;
import dev.notifyhub.api.web.dto.NotificationView;
import dev.notifyhub.api.web.validation.CreateNotificationRequestValidator;
import jakarta.validation.Valid;
import java.net.URI;
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

/** {@code POST /notifications} (202 + Location, D-02) e {@code GET /notifications/{id}} (D-03). */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

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

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreateNotificationResponse> create(@Valid @RequestBody CreateNotificationRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        CreateNotificationResponse response = service.create(request, idempotencyKey).response();
        return ResponseEntity.accepted()
                .location(URI.create("/notifications/" + response.id()))
                .body(response);
    }

    @GetMapping("/{id}")
    public NotificationView get(@PathVariable String id) {
        return service.get(id);
    }
}
