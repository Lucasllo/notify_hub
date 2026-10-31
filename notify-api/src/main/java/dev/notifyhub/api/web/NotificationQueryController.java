package dev.notifyhub.api.web;

import dev.notifyhub.api.application.NotificationQueryService;
import dev.notifyhub.api.web.dto.NotificationSummary;
import dev.notifyhub.api.web.dto.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Leitura operacional das notificações (API-05). Esqueleto do RED: ainda sem validação. */
@RestController
@RequestMapping("/notifications")
public class NotificationQueryController {

    private final NotificationQueryService service;

    public NotificationQueryController(NotificationQueryService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<NotificationSummary> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String channel, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.list(null, null, 0, 0);
    }
}
