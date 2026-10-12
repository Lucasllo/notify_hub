package dev.notifyhub.api.application;

import dev.notifyhub.api.messaging.NotificationPublisher;
import dev.notifyhub.api.messaging.PublishProperties;
import dev.notifyhub.api.messaging.PublishResult;
import dev.notifyhub.api.web.dto.CreateNotificationRequest;
import dev.notifyhub.api.web.dto.CreateNotificationResponse;
import dev.notifyhub.api.web.dto.NotificationView;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.domain.RecipientRules;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.persistence.NotificationDocument;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Criação e consulta de notificações. A API é dona só dos estados PENDING e PUBLISH_FAILED e nunca
 * grava status depois de um confirm positivo (o worker pode já estar processando).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final MongoTemplate mongoTemplate;
    private final NotificationPublisher publisher;
    private final PublishProperties props;

    public NotificationService(MongoTemplate mongoTemplate, NotificationPublisher publisher, PublishProperties props) {
        this.mongoTemplate = mongoTemplate;
        this.publisher = publisher;
        this.props = props;
    }

    public CreateNotificationResponse create(CreateNotificationRequest request) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();

        // API-01: o documento existe antes da mensagem
        mongoTemplate.insert(NotificationDocument.newPending(id, request.channel(), request.recipient(),
                request.templateCode(), request.variables(), now));
        log.info("Notificação {} criada (canal={}, destinatário={})", id, request.channel(),
                RecipientRules.mask(request.channel(), request.recipient()));

        PublishResult result = publisher.publish(
                new NotificationMessage(id, request.channel(), request.templateCode(), now), props.confirmTimeout());
        if (!result.ok()) {
            throw new IllegalStateException("Publicação não confirmada: " + result.errorCode());
        }
        return new CreateNotificationResponse(id, NotificationStatus.PENDING, now);
    }

    public Optional<NotificationView> find(String id) {
        return Optional.ofNullable(mongoTemplate.findById(id, NotificationDocument.class))
                .map(NotificationView::from);
    }
}
