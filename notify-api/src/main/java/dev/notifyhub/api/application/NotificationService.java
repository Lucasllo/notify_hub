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
 * grava status depois de um confirm positivo (o worker pode já estar processando).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

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
     * é lido pela chave. Com o mesmo hash, a resposta é um replay com o status <b>atual</b> do documento
     * (inclusive PUBLISH_FAILED; a republicação no replay vem no plano 03-05, D-08), sem publicar de novo.
     * Com outro hash, {@link IdempotencyKeyReusedException} (422), sem criar documento nem publicar.
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

        // Sem confirm e com o documento marcado PUBLISH_FAILED: 503 com Retry-After. Sem confirm, mas com
        // o worker já tendo reivindicado (update condicional casou 0), a mensagem chegou: segue o 202
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
        // Log sem a chave nem o destinatário: só o id da notificação original
        log.info("Replay de Idempotency-Key: devolvendo a notificação {} (status {})", existing.id(), existing.status());
        return new CreateResult(
                new CreateNotificationResponse(existing.id(), existing.status(), existing.createdAt()), true);
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
