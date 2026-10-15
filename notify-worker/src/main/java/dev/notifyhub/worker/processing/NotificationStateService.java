package dev.notifyhub.worker.processing;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.mongodb.client.result.UpdateResult;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.persistence.AttemptEntry;
import dev.notifyhub.common.persistence.LastError;
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
import org.springframework.data.mongodb.core.query.Criteria;
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
 *       ({@link NotificationStatus#claimableForDelivery()}, ou PROCESSING preso com
 *       {@code redelivered} ou lease vencido) e {@code retryCount ≤ h}. Duas entregas
 *       concorrentes da mesma mensagem produzem no máximo um claim, porque {@code findAndModify}
 *       num único documento é atômico.</li>
 *   <li><b>resultado</b> (SENT, FAILED, RETRYING ou DEAD_LETTERED): {@code updateFirst} cercado
 *       por {@code status = PROCESSING} e {@code retryCount = h} exatos. Se outro worker
 *       reivindicou de novo no meio (fence perdido), nada é gravado. Nenhum resultado grava
 *       {@code retryCount}.</li>
 *   <li><b>republicação falha</b>: RETRYING → DEAD_LETTERED cercado por {@code status = RETRYING}
 *       e {@code retryCount = h}, só com {@code lastError} (D-11, D-17).</li>
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
     * Reivindica a notificação para entrega no nível {@code retryCount} → PROCESSING, com
     * {@code claimedAt}, {@code leaseUntil} e {@code updatedAt} (WRK-03, D-09). O filtro tem o fence
     * {@code retryCount ≤ h}, que vale para os dois ramos, e um {@code $or} derivado da tabela:
     * <ul>
     *   <li>{@link NotificationStatus#claimableForDelivery()} (PENDING, RETRYING, PUBLISH_FAILED):
     *       sem condição;</li>
     *   <li>{@link NotificationStatus#reclaimableWhenStuck()} (PROCESSING abandonado por um worker
     *       derrubado): só quando a mensagem chega com {@code redelivered=true} ou, sem o flag,
     *       quando {@code leaseUntil < agora}. Um lease vigente nunca é tomado de um worker ativo; o
     *       lease ({@code notify.worker.lease}, 5m) é muito maior que o pior caso de uma tentativa
     *       (latência + confirms de 5s).</li>
     * </ul>
     *
     * <p><b>At-least-once (Pergunta em aberto 6, adiada):</b> o broker também marca
     * {@code redelivered} quando o canal de um worker <i>vivo</i> cai. Aí dois workers processam o
     * mesmo nível; o fence {@code status} + {@code retryCount} dos resultados garante que só um
     * resultado é gravado, mas o envio pode ter acontecido duas vezes. Endurecer o fence com
     * {@code claimedAt} fica para depois; o trade-off é documentado na Fase 4.
     *
     * @param redelivered flag {@code redelivered} da entrega AMQP
     * @return o documento já em PROCESSING, ou vazio se a mensagem for duplicada ou obsoleta (o
     *         documento não existe, já foi reivindicado/entregue, está num nível acima ou está em
     *         PROCESSING com lease vigente numa primeira entrega)
     */
    public Optional<NotificationDocument> claim(String id, int retryCount, boolean redelivered) {
        Instant now = Instant.now();
        Criteria fresh = where(Fields.STATUS).in(NotificationStatus.claimableForDelivery());
        Criteria stuck = where(Fields.STATUS).in(NotificationStatus.reclaimableWhenStuck());
        if (!redelivered) {
            stuck = stuck.and(Fields.LEASE_UNTIL).lt(now);
        }
        Query claimable = query(where(Fields.ID).is(id)
                .and(Fields.RETRY_COUNT).lte(retryCount)
                .orOperator(fresh, stuck));
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

    /**
     * Grava FAILED numa única atualização atômica (DLV-06): status, {@code lastError} e
     * {@code updatedAt}; remove {@code leaseUntil}; incrementa {@code attemptCount} e acrescenta a
     * tentativa ao fim de {@code attempts[]}, mantendo as últimas 50. {@code retryCount} não muda.
     *
     * @return {@code false} se o fence foi perdido; nada é gravado nesse caso
     */
    public boolean markFailed(String id, int retryCount, AttemptEntry attempt, LastError lastError) {
        return recordFailure(id, retryCount, NotificationStatus.FAILED, attempt, lastError);
    }

    /**
     * Grava RETRYING numa única atualização atômica (D-11, passo 1): mesmo molde do
     * {@link #markFailed}, com a tentativa no histórico e o {@code lastError} visível no GET.
     *
     * <p>{@code retryCount} <b>não</b> muda aqui: quem grava {@code h+1} é o claim do próximo token.
     * Se este update gravasse {@code h+1} e o worker caísse antes do publish, a reentrega da original
     * ({@code h}) seria cercada por {@code retryCount ≤ h} e a notificação ficaria RETRYING sem
     * mensagem em fila nenhuma (RESEARCH Armadilha 2).
     *
     * @return {@code false} se o fence foi perdido; nada é gravado nesse caso
     */
    public boolean markRetrying(String id, int retryCount, AttemptEntry attempt, LastError lastError) {
        return recordFailure(id, retryCount, NotificationStatus.RETRYING, attempt, lastError);
    }

    /**
     * Grava DEAD_LETTERED quando a escada acabou (DLV-05): mesmo molde do {@link #markRetrying}, com a
     * última tentativa no histórico e {@code lastError} TRANSIENT.
     *
     * @return {@code false} se o fence foi perdido; nada é gravado nesse caso
     */
    public boolean markDeadLettered(String id, int retryCount, AttemptEntry attempt, LastError lastError) {
        return recordFailure(id, retryCount, NotificationStatus.DEAD_LETTERED, attempt, lastError);
    }

    /**
     * RETRYING → DEAD_LETTERED quando a republicação para o próximo nível falhou (D-11): fence
     * {@code status = RETRYING} e {@code retryCount = h} exatos, só {@code $set} de status,
     * {@code lastError} (REPUBLISH_FAILED) e {@code updatedAt}. Sem {@code $push} nem {@code $inc}:
     * a tentativa já foi registrada pelo {@link #markRetrying} (D-17).
     *
     * @return {@code false} se o fence foi perdido (outro worker reivindicou a reentrega); nada é
     *         gravado nesse caso
     * @throws IllegalStateException se a tabela de transições não permitir RETRYING → DEAD_LETTERED
     */
    public boolean markDeadLetteredAfterRepublishFailure(String id, int retryCount, LastError lastError) {
        NotificationStatus from = NotificationStatus.RETRYING;
        NotificationStatus target = NotificationStatus.DEAD_LETTERED;
        if (!from.canTransitionTo(target)) {
            throw new IllegalStateException("transição não permitida: " + from + " -> " + target);
        }
        Instant now = Instant.now();
        Query fence = query(where(Fields.ID).is(id)
                .and(Fields.STATUS).is(from)
                .and(Fields.RETRY_COUNT).is(retryCount));
        Update deadLettered = new Update()
                .set(Fields.STATUS, target)
                .set(Fields.LAST_ERROR, lastError)
                .set(Fields.UPDATED_AT, now);

        UpdateResult result = mongo.updateFirst(fence, deadLettered, NotificationDocument.class);
        if (result.getModifiedCount() == 0) {
            log.warn("fence perdido: DEAD_LETTERED após republicação falha não gravado id={} retryCount={}",
                    id, retryCount);
            return false;
        }
        return true;
    }

    /**
     * Molde dos resultados de falha a partir de PROCESSING (FAILED, RETRYING e DEAD_LETTERED): mesmo
     * fence do {@link #markSent}, {@code $set} de status/lastError/updatedAt,
     * {@code $unset} do lease, {@code $inc} de attemptCount e {@code $push} limitado da tentativa.
     *
     * @throws IllegalStateException se a tabela de transições não permitir PROCESSING → {@code target}
     */
    private boolean recordFailure(String id, int retryCount, NotificationStatus target, AttemptEntry attempt,
                                  LastError lastError) {
        NotificationStatus from = NotificationStatus.PROCESSING;
        if (!from.canTransitionTo(target)) {
            throw new IllegalStateException("transição não permitida: " + from + " -> " + target);
        }
        Instant now = Instant.now();
        Query fence = query(where(Fields.ID).is(id)
                .and(Fields.STATUS).is(from)
                .and(Fields.RETRY_COUNT).is(retryCount));
        Update failed = new Update()
                .set(Fields.STATUS, target)
                .set(Fields.LAST_ERROR, lastError)
                .set(Fields.UPDATED_AT, now)
                .unset(Fields.LEASE_UNTIL)
                .inc(Fields.ATTEMPT_COUNT, 1);
        // $slice exige $each: slice(...) antes de each(...) monta os dois modificadores do $push
        failed.push(Fields.ATTEMPTS).slice(-50).each(attempt);

        UpdateResult result = mongo.updateFirst(fence, failed, NotificationDocument.class);
        if (result.getModifiedCount() == 0) {
            log.warn("fence perdido: resultado {} não gravado id={} retryCount={} (reclaim concorrente)",
                    target, id, retryCount);
            return false;
        }
        return true;
    }
}
