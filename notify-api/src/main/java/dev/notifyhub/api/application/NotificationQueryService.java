package dev.notifyhub.api.application;

import dev.notifyhub.api.web.dto.NotificationSummary;
import dev.notifyhub.api.web.dto.PageResponse;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/** Consultas de leitura das notificações (API-05). Esqueleto do RED: ainda não consulta o Mongo. */
@Service
public class NotificationQueryService {

    private final MongoTemplate mongo;

    public NotificationQueryService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public PageResponse<NotificationSummary> list(NotificationStatus status, Channel channel, int page, int size) {
        return new PageResponse<>(List.of(), page, size, 0, 0);
    }
}
