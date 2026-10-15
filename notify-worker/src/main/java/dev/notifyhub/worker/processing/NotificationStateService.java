package dev.notifyhub.worker.processing;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.mongodb.client.result.UpdateResult;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.persistence.AttemptEntry;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.common.persistence.NotificationDocument.Fields;
import dev.notifyhub.common.persistence.RenderedContent;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Transições de estado do worker no documento {@code notifications} (WRK-02, WRK-04). Cada
 * transição é uma única operação condicional no {@link MongoTemplate}; nunca leitura-modificação-
 * gravação nem {@code save()}, que sobrescreveria o histórico.
 *
 * <ul>
 *   <li><b>claim</b>: {@code findAndModify} que só casa se o status for reivindicável
 *       ({@link NotificationStatus#claimableForDelivery()}) e {@code retryCount ≤ h}. Duas entregas
 *       concorrentes da mesma mensagem produzem no máximo um claim, porque {@code findAndModify}
 *       num único documento é atômico.</li>
 *   <li><b>resultado</b>: {@code updateFirst} cercado por {@code status = PROCESSING} e
 *       {@code retryCount = h} exatos. Se outro worker reivindicou de novo no meio (fence perdido),
 *       nada é gravado.</li>
 * </ul>
 */
@Service
public class NotificationStateService {

    private static final Logger log = LoggerFactory.getLogger(NotificationStateService.class);

    private final MongoTemplate mongo;
    private final Duration lease;

    public NotificationStateService(MongoTemplate mongo,
                                    @Value("${notify.worker.lease:5m}") Duration lease) {
        this.mongo = mongo;
        this.lease = lease;
    }

    /**
     * Reivindica a notificação para entrega no nível {@code retryCount}: PENDING, RETRYING ou
     * PUBLISH_FAILED com {@code retryCount ≤ h} → PROCESSING, com {@code claimedAt},
     * {@code leaseUntil} e {@code updatedAt}.
     *
     * @return o documento já em PROCESSING, ou vazio se a mensagem for duplicada ou obsoleta (o
     *         documento não existe, já foi reivindicado/entregue ou está num nível acima)
     */
    public Optional<NotificationDocument> claim(String id, int retryCount) {
        Instant now = Instant.now();
        Query claimable = query(where(Fields.ID).is(id)
                .and(Fields.STATUS).in(NotificationStatus.claimableForDelivery())
                .and(Fields.RETRY_COUNT).lte(retryCount));
        Update toProcessing = new Update()
                .set(Fields.STATUS, NotificationStatus.PROCESSING)
                .set(Fields.RETRY_COUNT, retryCount)
                .set(Fields.CLAIMED_AT, now)
                .set(Fields.LEASE_UNTIL, now.plus(lease))
                .set(Fields.UPDATED_AT, now);
        return Optional.ofNullable(mongo.findAndModify(claimable, toProcessing,
                FindAndModifyOptions.options().returnNew(true), NotificationDocument.class));
    }

    /**
     * Grava SENT numa única atualização atômica (D-03): status, conteúdo renderizado, {@code sentAt}
     * e {@code updatedAt}; remove {@code lastError} e {@code leaseUntil}; incrementa
     * {@code attemptCount} e acrescenta a tentativa ao fim de {@code attempts[]}, mantendo as
     * últimas 50.
     *
     * @return {@code false} se o fence foi perdido (o documento não está mais em PROCESSING no mesmo
     *         {@code retryCount}); nada é gravado nesse caso
     */
    public boolean markSent(String id, int retryCount, RenderedContent rendered, AttemptEntry attempt) {
        Instant now = Instant.now();
        Query fence = query(where(Fields.ID).is(id)
                .and(Fields.STATUS).is(NotificationStatus.PROCESSING)
                .and(Fields.RETRY_COUNT).is(retryCount));
        Update sent = new Update()
                .set(Fields.STATUS, NotificationStatus.SENT)
                .set(Fields.RENDERED, rendered)
                .set(Fields.SENT_AT, now)
                .set(Fields.UPDATED_AT, now)
                .unset(Fields.LAST_ERROR)
                .unset(Fields.LEASE_UNTIL)
                .inc(Fields.ATTEMPT_COUNT, 1);
        // $slice exige $each: slice(...) antes de each(...) monta os dois modificadores do $push
        sent.push(Fields.ATTEMPTS).slice(-50).each(attempt);

        UpdateResult result = mongo.updateFirst(fence, sent, NotificationDocument.class);
        if (result.getModifiedCount() == 0) {
            log.warn("fence perdido: resultado SENT não gravado id={} retryCount={} (reclaim concorrente)",
                    id, retryCount);
            return false;
        }
        return true;
    }

    /** Esqueleto (RED). */
    public boolean markFailed(String id, int retryCount, AttemptEntry attempt,
                              dev.notifyhub.common.persistence.LastError lastError) {
        throw new UnsupportedOperationException("markFailed pendente");
    }
}
