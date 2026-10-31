package dev.notifyhub.api.application;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.mongodb.client.result.UpdateResult;
import dev.notifyhub.api.messaging.NotificationPublisher;
import dev.notifyhub.api.messaging.PublishProperties;
import dev.notifyhub.api.messaging.PublishResult;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.persistence.LastError;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.common.persistence.NotificationDocument.Fields;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Esqueleto do RED (03-05): publish da criação movido do {@code NotificationService} sem mudança;
 * republish e lote ainda sem comportamento.
 */
@Service
public class NotificationRepublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationRepublisher.class);

    private final MongoTemplate mongoTemplate;
    private final NotificationPublisher publisher;
    private final PublishProperties props;

    public NotificationRepublisher(MongoTemplate mongoTemplate, NotificationPublisher publisher,
                                   PublishProperties props) {
        this.mongoTemplate = mongoTemplate;
        this.publisher = publisher;
        this.props = props;
    }

    /** Resultado de um republish. */
    public record RepublishOutcome(Kind kind, PublishResult failure) {

        /** Desfechos do republish. */
        public enum Kind {
            REPUBLISHED,
            NOT_PUBLISH_FAILED,
            FAILED
        }
    }

    /** Contagens do lote. */
    public record RepublishReport(int requested, int republished) {
    }

    public Optional<PublishResult> publishOrMarkFailed(NotificationDocument doc) {
        PublishResult result = publisher.publish(
                new NotificationMessage(doc.id(), doc.channel(), doc.templateCode(), doc.createdAt()),
                props.confirmTimeout());
        if (result.ok()) {
            return Optional.empty();
        }
        Instant failedAt = Instant.now();
        UpdateResult update = mongoTemplate.updateFirst(
                query(where(Fields.ID).is(doc.id()).and(Fields.STATUS).is(NotificationStatus.PENDING)),
                new Update()
                        .set(Fields.STATUS, NotificationStatus.PUBLISH_FAILED)
                        .set(Fields.LAST_ERROR, new LastError(result.errorCode(), result.detail(), null, failedAt))
                        .set(Fields.UPDATED_AT, failedAt),
                NotificationDocument.class);
        if (update.getModifiedCount() == 1) {
            log.warn("Notificação {} marcada PUBLISH_FAILED ({})", doc.id(), result.errorCode());
            return Optional.of(result);
        }
        return Optional.empty();
    }

    public RepublishOutcome republish(NotificationDocument doc) {
        return new RepublishOutcome(RepublishOutcome.Kind.NOT_PUBLISH_FAILED, null);
    }

    public RepublishReport republishFailed(int max) {
        return new RepublishReport(max, 0);
    }
}
