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
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Serviço único de publish e republish da API. É usado pela criação ({@code POST /notifications}),
 * pelo replay de uma notificação PUBLISH_FAILED com a mesma {@code Idempotency-Key} (D-08) e pelo
 * endpoint administrativo {@code POST /admin/notifications/republish-failed} (ADM-03), para que os
 * três caminhos tenham exatamente as mesmas transições e o mesmo {@code lastError}.
 *
 * <p>Transições, todas condicionais ao status lido (Padrão 4b):
 * <ul>
 *   <li>publish sem confirm: PENDING → PUBLISH_FAILED. Se o update casar 0 documentos, o worker já
 *       reivindicou a notificação, ou seja, a mensagem chegou (Armadilha 4 da Fase 1);</li>
 *   <li>republish: PUBLISH_FAILED → PENDING antes do publish confirmado. Se casar 0 documentos, outro
 *       caminho já agiu (o worker ou outro republish) e nada é publicado, o que evita um segundo envio
 *       ao destinatário (T-03-22).</li>
 * </ul>
 *
 * <p>O {@code lastError.message} de PUBLISH_FAILED é uma mensagem fixa derivada do código, nunca o
 * texto livre do broker (reply text, motivo do nack ou mensagem de exceção), que é não confiável e
 * apareceria no {@code GET /notifications/{id}} (T-02-01, T-03-21).
 */
@Service
public class NotificationRepublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationRepublisher.class);

    /**
     * Motivo do {@link RepublishInterruptedException} quando o MongoDB falha na busca ou num update do
     * lote. Os demais motivos vêm de {@link PublishResult#errorCode()}.
     */
    public static final String MONGO_UNAVAILABLE = "MONGO_UNAVAILABLE";

    private final MongoTemplate mongoTemplate;
    private final NotificationPublisher publisher;
    private final PublishProperties props;

    public NotificationRepublisher(MongoTemplate mongoTemplate, NotificationPublisher publisher,
                                   PublishProperties props) {
        this.mongoTemplate = mongoTemplate;
        this.publisher = publisher;
        this.props = props;
    }

    /**
     * Resultado de um {@link #republish republish}.
     *
     * @param kind    o que aconteceu
     * @param failure resultado do publish quando {@code kind} é {@link Kind#FAILED}; nulo nos outros casos
     */
    public record RepublishOutcome(Kind kind, PublishResult failure) {

        /** Desfechos do republish. */
        public enum Kind {
            /** PUBLISH_FAILED → PENDING e publish confirmado. */
            REPUBLISHED,
            /** O documento já não estava PUBLISH_FAILED: nada foi publicado. */
            NOT_PUBLISH_FAILED,
            /** Publish sem confirm: o documento voltou a PUBLISH_FAILED. */
            FAILED
        }

        static RepublishOutcome republished() {
            return new RepublishOutcome(Kind.REPUBLISHED, null);
        }

        static RepublishOutcome notPublishFailed() {
            return new RepublishOutcome(Kind.NOT_PUBLISH_FAILED, null);
        }

        static RepublishOutcome failed(PublishResult failure) {
            return new RepublishOutcome(Kind.FAILED, failure);
        }
    }

    /**
     * Contagens do lote do {@code republish-failed}.
     *
     * @param requested o {@code max} pedido
     * @param republished quantas foram republicadas com confirm
     */
    public record RepublishReport(int requested, int republished) {
    }

    /**
     * Publica a notificação com confirm. Sem confirm, faz PENDING → PUBLISH_FAILED condicional.
     *
     * @return vazio se o broker confirmou, ou se o update condicional casou 0 documentos (o worker já
     *         reivindicou, então a mensagem chegou); o {@link PublishResult} da falha se o documento
     *         ficou PUBLISH_FAILED
     */
    public Optional<PublishResult> publishOrMarkFailed(NotificationDocument doc) {
        PublishResult result = publisher.publish(
                new NotificationMessage(doc.id(), doc.channel(), doc.templateCode(), doc.createdAt()),
                props.confirmTimeout());
        if (result.ok()) {
            return Optional.empty();
        }
        if (markPublishFailed(doc.id(), result)) {
            return Optional.of(result);
        }
        log.info("Publicação da notificação {} sem confirm ({}), mas o worker já a reivindicou; seguindo como publicada",
                doc.id(), result.errorCode());
        return Optional.empty();
    }

    /**
     * Republica uma notificação PUBLISH_FAILED: PUBLISH_FAILED → PENDING condicional e depois
     * {@link #publishOrMarkFailed publish confirmado}.
     */
    public RepublishOutcome republish(NotificationDocument doc) {
        UpdateResult update = mongoTemplate.updateFirst(
                query(where(Fields.ID).is(doc.id()).and(Fields.STATUS).is(NotificationStatus.PUBLISH_FAILED)),
                new Update()
                        .set(Fields.STATUS, NotificationStatus.PENDING)
                        .set(Fields.UPDATED_AT, Instant.now()),
                NotificationDocument.class);
        if (update.getModifiedCount() == 0) {
            // T-03-22: outro caminho já tirou a notificação de PUBLISH_FAILED; publicar geraria um segundo envio
            log.info("Notificação {} já não estava PUBLISH_FAILED; nada republicado", doc.id());
            return RepublishOutcome.notPublishFailed();
        }
        Optional<PublishResult> failure = publishOrMarkFailed(doc);
        if (failure.isPresent()) {
            return RepublishOutcome.failed(failure.get());
        }
        log.info("Notificação {} republicada", doc.id());
        return RepublishOutcome.republished();
    }

    /**
     * Republica até {@code max} notificações PUBLISH_FAILED, da mais antiga para a mais nova
     * ({@code createdAt} asc, {@code _id} asc). Só as REPUBLISHED contam; um republish que perde o
     * fence (outro caminho já agiu) não conta nem interrompe.
     *
     * <p>A primeira falha para o lote (padrão do D-03): publish sem confirm lança
     * {@link RepublishInterruptedException} com {@code reason} = {@link PublishResult#errorCode()}, e
     * uma {@link DataAccessException} na busca ou num update lança com {@link #MONGO_UNAVAILABLE}. As
     * duas levam as contagens acumuladas; as notificações não processadas continuam PUBLISH_FAILED.
     *
     * @param max já validado pelo controller (1..{@code notify.admin.max-limit})
     */
    public RepublishReport republishFailed(int max) {
        int republished = 0;
        try {
            Query pending = query(where(Fields.STATUS).is(NotificationStatus.PUBLISH_FAILED))
                    .with(Sort.by(Sort.Order.asc(Fields.CREATED_AT), Sort.Order.asc(Fields.ID)))
                    .limit(max);
            List<NotificationDocument> candidates = mongoTemplate.find(pending, NotificationDocument.class);
            for (NotificationDocument doc : candidates) {
                RepublishOutcome outcome = republish(doc);
                if (outcome.kind() == RepublishOutcome.Kind.FAILED) {
                    String reason = outcome.failure().errorCode();
                    log.warn("Republicação em lote interrompida na notificação {} ({}); {} republicadas",
                            doc.id(), reason, republished);
                    throw new RepublishInterruptedException(new RepublishReport(max, republished), reason);
                }
                if (outcome.kind() == RepublishOutcome.Kind.REPUBLISHED) {
                    republished++;
                }
            }
        } catch (DataAccessException e) {
            // T-02-01: o log e o 503 citam só o motivo fechado, nunca a mensagem da exceção
            log.warn("Republicação em lote interrompida: MongoDB indisponível ({}); {} republicadas",
                    e.getClass().getSimpleName(), republished);
            throw new RepublishInterruptedException(new RepublishReport(max, republished), MONGO_UNAVAILABLE);
        }
        return new RepublishReport(max, republished);
    }

    /**
     * PENDING → PUBLISH_FAILED, condicional ao status PENDING, com {@code lastError} de mensagem fixa.
     *
     * @return {@code true} se o documento foi marcado; {@code false} se o update casou 0 documentos
     */
    private boolean markPublishFailed(String id, PublishResult result) {
        Instant failedAt = Instant.now();
        String code = result.errorCode();
        UpdateResult update = mongoTemplate.updateFirst(
                query(where(Fields.ID).is(id).and(Fields.STATUS).is(NotificationStatus.PENDING)),
                new Update()
                        .set(Fields.STATUS, NotificationStatus.PUBLISH_FAILED)
                        .set(Fields.LAST_ERROR, new LastError(code, fixedMessage(code), null, failedAt))
                        .set(Fields.UPDATED_AT, failedAt),
                NotificationDocument.class);
        if (update.getModifiedCount() == 1) {
            log.warn("Notificação {} marcada PUBLISH_FAILED ({})", id, code);
            return true;
        }
        return false;
    }

    /** T-02-01: só o código, do conjunto fechado de {@link PublishResult#errorCode()}. */
    private static String fixedMessage(String code) {
        return "Publicação não confirmada pelo broker (" + code + ")";
    }
}
