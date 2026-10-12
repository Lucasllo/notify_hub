package dev.notifyhub.api.application;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.mongodb.client.result.UpdateResult;
import dev.notifyhub.api.messaging.NotificationPublisher;
import dev.notifyhub.api.messaging.PublishProperties;
import dev.notifyhub.api.messaging.PublishResult;
import dev.notifyhub.api.web.dto.CreateNotificationRequest;
import dev.notifyhub.api.web.dto.CreateNotificationResponse;
import dev.notifyhub.api.web.dto.NotificationView;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.domain.RecipientRules;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.persistence.LastError;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.common.persistence.NotificationDocument.Fields;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
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
            markPublishFailed(id, result);
        }
        // Depois de um confirm positivo nada é gravado aqui: o worker pode já estar processando
        return new CreateNotificationResponse(id, NotificationStatus.PENDING, now);
    }

    /**
     * PENDING → PUBLISH_FAILED, condicional ao status PENDING (coerente com
     * {@code NotificationStatus.PENDING.canTransitionTo(PUBLISH_FAILED)}). Se casar 0 documentos, o
     * worker já reivindicou a notificação, ou seja, a mensagem chegou: segue para o 202 (Armadilha 4).
     */
    private void markPublishFailed(String id, PublishResult result) {
        Instant failedAt = Instant.now();
        UpdateResult update = mongoTemplate.updateFirst(
                query(where(Fields.ID).is(id).and(Fields.STATUS).is(NotificationStatus.PENDING)),
                new Update()
                        .set(Fields.STATUS, NotificationStatus.PUBLISH_FAILED)
                        .set(Fields.LAST_ERROR, new LastError(result.errorCode(), result.detail(), null, failedAt))
                        .set(Fields.UPDATED_AT, failedAt),
                NotificationDocument.class);
        if (update.getModifiedCount() == 1) {
            log.warn("Notificação {} marcada PUBLISH_FAILED ({})", id, result.errorCode());
            throw new PublishFailedException(id, props.retryAfter(), result);
        }
        log.info("Publicação da notificação {} sem confirm ({}), mas o worker já a reivindicou; respondendo 202",
                id, result.errorCode());
    }

    public Optional<NotificationView> find(String id) {
        return Optional.ofNullable(mongoTemplate.findById(id, NotificationDocument.class))
                .map(NotificationView::from);
    }
}
