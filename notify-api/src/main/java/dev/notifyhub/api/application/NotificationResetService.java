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
        Query fence = query(where(Fields.ID).is(id)
                .and(Fields.STATUS).is(previousStatus)
                .and(Fields.RETRY_COUNT).is(current.retryCount()));
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
    public sealed interface ResetResult permits Reset, Missing, Sent, NotResettable, FenceLost {
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

    /** O documento mudou entre a leitura e o reset; nada foi gravado. */
    public record FenceLost() implements ResetResult {
    }
}
