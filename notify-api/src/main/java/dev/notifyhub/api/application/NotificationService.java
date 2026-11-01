package dev.notifyhub.api.application;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import dev.notifyhub.api.messaging.PublishProperties;
import dev.notifyhub.api.web.dto.CreateNotificationRequest;
import dev.notifyhub.api.web.dto.CreateNotificationResponse;
import dev.notifyhub.api.web.dto.NotificationView;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.domain.RecipientRules;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.common.persistence.NotificationDocument.Fields;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Criação e consulta de notificações. A API é dona só dos estados PENDING e PUBLISH_FAILED e nunca
 * grava status depois de um confirm positivo (o worker pode já estar processando). O publish e a
 * marcação PUBLISH_FAILED ficam no {@link NotificationRepublisher}, o mesmo serviço do endpoint
 * administrativo {@code republish-failed}.
 *
 * <p>Todo 202, de criação ou de replay com a mesma {@code Idempotency-Key}, se apoia num confirm próprio
 * do broker ou em evidência de claim do worker ({@link NotificationRepublisher#wasClaimedByWorker}).
 * PENDING sozinho nunca prova que a mensagem chegou (Gap 1 do 03-VERIFICATION, D-07, D-08).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** Rodadas do laço do replay antes de publicar sem fence. */
    static final int MAX_REPLAY_ROUNDS = 3;

    /** Janela de publicação de um PENDING: {@code updatedAt} + este fator × {@code confirm-timeout}. */
    static final int PUBLISH_WINDOW_FACTOR = 2;

    private final MongoTemplate mongoTemplate;
    private final NotificationRepublisher republisher;
    private final PublishProperties props;

    public NotificationService(MongoTemplate mongoTemplate, NotificationRepublisher republisher,
                               PublishProperties props) {
        this.mongoTemplate = mongoTemplate;
        this.republisher = republisher;
        this.props = props;
    }

    /**
     * Resultado da criação.
     *
     * @param response corpo do 202 ({@code id}, {@code status}, {@code createdAt})
     * @param replayed {@code true} quando a resposta é a notificação de um POST anterior com a mesma
     *                 {@code Idempotency-Key} (o controller acrescenta {@code Idempotent-Replayed: true})
     */
    public record CreateResult(CreateNotificationResponse response, boolean replayed) {
    }

    /**
     * Cria a notificação (insert PENDING → publish com confirm) ou, com {@code idempotencyKey}, devolve
     * a notificação de um POST anterior com a mesma chave e o mesmo payload (API-03, D-05..D-07).
     *
     * <p>Com chave, o documento leva a chave e o {@link RequestHasher#hash hash do pedido canônico}. A
     * atomicidade entre requisições vem só do índice único parcial {@code idempotencyKey_unique}: o
     * insert de uma chave já gravada lança {@link DuplicateKeyException}, e então o documento existente
     * é lido pela chave. Com o mesmo hash, a resposta é um replay, e todo 202 de replay tem confirm próprio
     * ou evidência de claim: o documento já reivindicado pelo worker responde com o status <b>atual</b>,
     * sem publicar de novo (D-07); PUBLISH_FAILED (D-08) e PENDING sem confirm (janela de publicação
     * vencida, Gap 1) são republicados pelo mesmo serviço do {@code republish-failed}, com fence pelo
     * status lido, e respondem 202 PENDING ou 503 com {@code Retry-After} (ver {@code replay}).
     * Com outro hash, {@link IdempotencyKeyReusedException} (422), antes de qualquer leitura extra, sem
     * criar documento nem publicar.
     *
     * <p>A corrida entre dois POSTs com a mesma chave nova é resolvida pelo índice único: um insert
     * vence, o perdedor recebe {@code DuplicateKeyException}, lê o vencedor pela chave e segue a mesma
     * regra de replay ou 422 (D-05).
     *
     * <p>Uma {@code DuplicateKeyException} sem chave, ou cujo documento não é achado pela chave
     * (duplicata em outro índice único), é relançada, nunca tratada como replay (Armadilha 6).
     *
     * @param idempotencyKey chave do header {@code Idempotency-Key}, já validada pelo controller; nula
     *                       quando o POST não traz o header
     */
    public CreateResult create(CreateNotificationRequest request, String idempotencyKey) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String requestHash = idempotencyKey == null ? null : RequestHasher.hash(request);

        // API-01: o documento existe antes da mensagem
        NotificationDocument doc = NotificationDocument.newPending(id, request.channel(), request.recipient(),
                request.templateCode(), request.variables(), idempotencyKey, requestHash, now);
        try {
            mongoTemplate.insert(doc);
        } catch (DuplicateKeyException e) {
            if (idempotencyKey == null) {
                throw e;
            }
            return replayOrRethrow(idempotencyKey, requestHash, e);
        }
        log.info("Notificação {} criada (canal={}, destinatário={})", id, request.channel(),
                RecipientRules.mask(request.channel(), request.recipient()));

        // Sem confirm e sem evidência de claim do worker: 503 com Retry-After. Sem confirm, mas com a
        // releitura mostrando o claim do worker (NotificationRepublisher.wasClaimedByWorker), a mensagem
        // chegou: segue o 202
        republisher.publishOrMarkFailed(doc).ifPresent(result -> {
            throw new PublishFailedException(id, props.retryAfter(), result);
        });
        // Depois de um confirm positivo nada é gravado aqui: o worker pode já estar processando
        return new CreateResult(new CreateNotificationResponse(id, NotificationStatus.PENDING, now), false);
    }

    /**
     * O insert perdeu para um documento com a mesma chave: replay com o mesmo hash, 422 com outro. É
     * também o caminho do perdedor numa corrida de POSTs com a mesma chave nova (D-05).
     */
    private CreateResult replayOrRethrow(String idempotencyKey, String requestHash, DuplicateKeyException e) {
        NotificationDocument existing = mongoTemplate.findOne(
                query(where(Fields.IDEMPOTENCY_KEY).is(idempotencyKey)), NotificationDocument.class);
        if (existing == null) {
            // Armadilha 6: a duplicata foi em outro índice único
            throw e;
        }
        if (!requestHash.equals(existing.requestHash())) {
            // D-06: log sem a chave; o 422 também não ecoa a chave nem o id original
            log.info("Idempotency-Key reusada com outro payload (notificação original {}); respondendo 422",
                    existing.id());
            throw new IdempotencyKeyReusedException();
        }
        return replay(existing);
    }

    /**
     * Replay com a mesma {@code Idempotency-Key} e o mesmo payload. Regra única (Gap 1 do
     * 03-VERIFICATION): todo 202 se apoia em evidência de claim do worker
     * ({@link NotificationRepublisher#wasClaimedByWorker}) ou num confirm próprio do broker. PENDING não
     * prova que a mensagem chegou.
     *
     * <p>Laço sobre o documento corrente, limitado a {@link #MAX_REPLAY_ROUNDS} rodadas. Em cada uma:
     * <ol>
     *   <li>reivindicado pelo worker → 202 com o status atual, sem publicar (D-07);</li>
     *   <li>PENDING dentro da {@link #publishWindowEnd janela de publicação} → 202 com o status atual
     *       (comportamento do 03-04);</li>
     *   <li>PUBLISH_FAILED (D-08) ou PENDING com a janela vencida → {@link NotificationRepublisher#republish}
     *       cercado pelo status lido, o mesmo serviço do {@code republish-failed}. Republicada → 202 PENDING;
     *       publish sem confirm → 503 com {@code Retry-After}; fence perdido → relê e decide na rodada
     *       seguinte.</li>
     * </ol>
     * Esgotadas as rodadas sem decisão (três interferências concorrentes seguidas), o documento reivindicado
     * responde 202 com o status atual; senão o replay publica sem fence ({@link
     * NotificationRepublisher#publishOrMarkFailed}) e responde pelo próprio confirm. Uma cópia extra da
     * mensagem é descartada pelo claim exclusivo do worker, sem segundo envio.
     */
    private CreateResult replay(NotificationDocument existing) {
        NotificationDocument current = existing;
        for (int round = 1; round <= MAX_REPLAY_ROUNDS; round++) {
            if (NotificationRepublisher.wasClaimedByWorker(current.status())) {
                return replayed(current, current.status());
            }
            if (current.status() == NotificationStatus.PENDING && Instant.now().isBefore(publishWindowEnd(current))) {
                return replayed(current, current.status());
            }
            NotificationRepublisher.RepublishOutcome outcome = republisher.republish(current);
            switch (outcome.kind()) {
                case REPUBLISHED -> {
                    log.info("Replay de Idempotency-Key: notificação {} republicada", current.id());
                    return replayed(current, NotificationStatus.PENDING);
                }
                case FAILED -> throw new PublishFailedException(current.id(), props.retryAfter(), outcome.failure());
                case FENCE_LOST -> current = reread(current.id());
            }
        }
        if (NotificationRepublisher.wasClaimedByWorker(current.status())) {
            return replayed(current, current.status());
        }
        log.warn("Replay de Idempotency-Key: notificação {} perdeu o fence em {} rodadas; publicando sem fence",
                current.id(), MAX_REPLAY_ROUNDS);
        String id = current.id();
        republisher.publishOrMarkFailed(current).ifPresent(result -> {
            throw new PublishFailedException(id, props.retryAfter(), result);
        });
        return replayed(current, NotificationStatus.PENDING);
    }

    /** 202 de replay (D-07). Log sem a chave nem o destinatário: só o id e o status (T-03-40). */
    private static CreateResult replayed(NotificationDocument doc, NotificationStatus status) {
        log.info("Replay de Idempotency-Key: devolvendo a notificação {} (status {})", doc.id(), status);
        return new CreateResult(new CreateNotificationResponse(doc.id(), status, doc.createdAt()), true);
    }

    /**
     * Fim da janela de publicação: {@code updatedAt} + {@link #PUBLISH_WINDOW_FACTOR} ×
     * {@code confirm-timeout}. Depois dele, quem publicou esse PENDING (a criação ou um republish) já
     * teria respondido. Sem {@code updatedAt}, a janela é considerada vencida.
     */
    private Instant publishWindowEnd(NotificationDocument doc) {
        if (doc.updatedAt() == null) {
            return Instant.EPOCH;
        }
        return doc.updatedAt().plus(props.confirmTimeout().multipliedBy(PUBLISH_WINDOW_FACTOR));
    }

    /** Relê o documento pelo id. Ausente só acontece na limpeza dos ITs. */
    private NotificationDocument reread(String id) {
        NotificationDocument doc = mongoTemplate.findById(id, NotificationDocument.class);
        if (doc == null) {
            throw new IllegalStateException("Notificação " + id + " sumiu durante o replay de Idempotency-Key");
        }
        return doc;
    }

    public Optional<NotificationView> find(String id) {
        return Optional.ofNullable(mongoTemplate.findById(id, NotificationDocument.class))
                .map(NotificationView::from);
    }

    /** Como {@link #find(String)}, mas id inexistente vira {@link NotificationNotFoundException} (404). */
    public NotificationView get(String id) {
        return find(id).orElseThrow(() -> new NotificationNotFoundException(id));
    }
}
