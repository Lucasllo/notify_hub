package dev.notifyhub.api.application;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import dev.notifyhub.common.domain.AttemptOutcome;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.persistence.AttemptEntry;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.common.persistence.NotificationDocument.Fields;
import java.time.Instant;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Reset atômico de uma notificação para o reprocessamento da DLQ (ADM-01, ADM-02).
 *
 * <p><b>Ordem obrigatória (notas do ROADMAP):</b> o reset no Mongo acontece <em>antes</em> do
 * republish. Publicando primeiro, o worker poderia receber a mensagem com o documento ainda
 * DEAD_LETTERED (que não é reivindicável), confirmá-la como obsoleta e deixar o documento sem
 * mensagem em fila nenhuma.
 *
 * <p><b>Filtro derivado da tabela:</b> só age quando
 * {@code statusAnterior.canTransitionTo(PENDING)} na tabela de {@link NotificationStatus}; nenhum
 * status é escrito à mão aqui. O update é um único {@code findAndModify} cercado por {@code _id} +
 * status lido + {@code retryCount} lido (fence otimista): se outro processo mexeu no documento entre
 * a leitura e a escrita, nada é gravado.
 *
 * <p><b>Não terminais e lease (D-01, CR-01 da Fase 2):</b> além de FAILED e DEAD_LETTERED, a tabela
 * deixa PENDING, RETRYING e PROCESSING irem a PENDING, para que um documento não terminal cuja
 * mensagem caiu na DLQ (falha do Mongo no claim ou no update de resultado) seja retomado em vez de
 * travar a cabeça da DLQ. PROCESSING é guardado por lease ({@link NotificationStatus#reclaimableWhenStuck()}):
 * com {@code leaseUntil} vigente o resultado é {@link LeaseActive}, sem escrita, porque um worker pode
 * estar com ele; com lease vencido ou ausente, o fence ganha um {@code $or} de
 * {@code leaseUntil < agora} e {@code leaseUntil} nulo, para o reset nunca tomar um documento que um
 * worker acabou de reivindicar.
 *
 * <p><b>Histórico (ADM-02):</b> o reset grava status PENDING e {@code retryCount} 0, remove
 * {@code leaseUntil} e {@code claimedAt} e acrescenta ao fim de {@code attempts[]} (mantendo as
 * últimas 50) uma entrada {@link AttemptOutcome#REPROCESSED} com {@code number} 0,
 * {@code retryLevel} = {@code retryCount} anterior e duração 0. As entradas anteriores ficam como
 * estão. {@code attemptCount} fica intocado de propósito: o destinatário mágico
 * {@code fail-until-reprocess} conta tentativas por ele, e zerá-lo faria a notificação falhar para
 * sempre; incrementá-lo faria o evento parecer uma tentativa (Armadilha 8). {@code lastError} é
 * preservado como histórico. Nunca usa {@code save()}, que sobrescreveria o documento.
 *
 * <p><b>Resultados</b> ({@link ResetResult}):
 * <ul>
 *   <li>{@link Reset}: o documento foi para PENDING com {@code retryCount} 0, sem lease;</li>
 *   <li>{@link Missing}: o documento não existe;</li>
 *   <li>{@link Sent}: o documento já foi entregue;</li>
 *   <li>{@link NotResettable}: a tabela não deixa o status atual ir a PENDING;</li>
 *   <li>{@link LeaseActive}: PROCESSING com lease vigente, nada foi gravado;</li>
 *   <li>{@link FenceLost}: o documento mudou entre a leitura e o reset.</li>
 * </ul>
 */
@Service
public class NotificationResetService {

    private final MongoTemplate mongo;

    public NotificationResetService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * Lê o documento, classifica e, quando a tabela permite, grava o reset num único
     * {@code findAndModify}.
     *
     * @param id id da notificação
     * @return o resultado; nunca nulo
     */
    public ResetResult resetForReprocess(String id) {
        NotificationDocument current = mongo.findById(id, NotificationDocument.class);
        if (current == null) {
            return new Missing();
        }
        NotificationStatus previousStatus = current.status();
        if (previousStatus == NotificationStatus.SENT) {
            return new Sent();
        }
        if (!previousStatus.canTransitionTo(NotificationStatus.PENDING)) {
            return new NotResettable(previousStatus);
        }

        Instant now = Instant.now();
        // Status guardados por lease (PROCESSING, derivado da tabela): um worker pode estar com ele
        boolean leaseGuarded = NotificationStatus.reclaimableWhenStuck().contains(previousStatus);
        Instant leaseUntil = current.leaseUntil();
        if (leaseGuarded && leaseUntil != null && !leaseUntil.isBefore(now)) {
            return new LeaseActive(leaseUntil); // D-02: sem escrita; a mensagem vai para o fim da DLQ
        }

        Criteria fenceCriteria = where(Fields.ID).is(id)
                .and(Fields.STATUS).is(previousStatus)
                .and(Fields.RETRY_COUNT).is(current.retryCount());
        if (leaseGuarded) {
            // O lease precisa continuar vencido (ou ausente) no momento da escrita: um worker que
            // reivindicou o documento entre a leitura e o reset renova o lease e ganha a corrida
            fenceCriteria = fenceCriteria.orOperator(where(Fields.LEASE_UNTIL).lt(now),
                    where(Fields.LEASE_UNTIL).is(null));
        }
        Query fence = query(fenceCriteria);
        Update reset = new Update()
                .set(Fields.STATUS, NotificationStatus.PENDING)
                .set(Fields.RETRY_COUNT, 0)
                .set(Fields.UPDATED_AT, now)
                .unset(Fields.LEASE_UNTIL)
                .unset(Fields.CLAIMED_AT);
        // Mensagem montada só de nomes de enum, sem texto livre
        AttemptEntry reprocessed = new AttemptEntry(0, current.retryCount(), now, 0L, AttemptOutcome.REPROCESSED,
                null, "Reprocessada da DLQ pelo operador (status anterior: " + previousStatus.name() + ")", null,
                Thread.currentThread().toString());
        // $slice exige $each: slice(...) antes de each(...) monta os dois modificadores do $push (como no markSent)
        reset.push(Fields.ATTEMPTS).slice(-50).each(reprocessed);

        NotificationDocument updated = mongo.findAndModify(fence, reset,
                FindAndModifyOptions.options().returnNew(true), NotificationDocument.class);
        return updated == null ? new FenceLost() : new Reset(updated, previousStatus);
    }

    /** Resultado de {@link #resetForReprocess(String)}. */
    public sealed interface ResetResult permits Reset, Missing, Sent, NotResettable, LeaseActive, FenceLost {
    }

    /**
     * O reset foi gravado.
     *
     * @param document       o documento já em PENDING
     * @param previousStatus o status antes do reset
     */
    public record Reset(NotificationDocument document, NotificationStatus previousStatus) implements ResetResult {
    }

    /** O documento não existe. */
    public record Missing() implements ResetResult {
    }

    /** O documento já está SENT: não há mais nada a entregar. */
    public record Sent() implements ResetResult {
    }

    /**
     * A tabela não deixa o status atual ir a PENDING.
     *
     * @param status o status atual
     */
    public record NotResettable(NotificationStatus status) implements ResetResult {
    }

    /**
     * O documento está em PROCESSING com lease vigente: um worker pode estar entregando agora (D-02).
     * Nada foi gravado; o {@code DlqReprocessService} copia a mensagem para o fim da DLQ
     * ({@code skipped}) e o próximo reprocess a retoma depois que o lease vencer.
     *
     * @param leaseUntil fim do lease lido
     */
    public record LeaseActive(Instant leaseUntil) implements ResetResult {
    }

    /** O documento mudou entre a leitura e o reset; nada foi gravado. */
    public record FenceLost() implements ResetResult {
    }
}
