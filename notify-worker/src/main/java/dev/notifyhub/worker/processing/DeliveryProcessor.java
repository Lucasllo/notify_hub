package dev.notifyhub.worker.processing;

import dev.notifyhub.common.domain.AttemptOutcome;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.FailureClass;
import dev.notifyhub.common.domain.RecipientRules;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.messaging.TopologyProperties;
import dev.notifyhub.common.messaging.TtlLabel;
import dev.notifyhub.common.persistence.AttemptEntry;
import dev.notifyhub.common.persistence.LastError;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.common.persistence.RenderedContent;
import dev.notifyhub.worker.channel.AttemptContext;
import dev.notifyhub.worker.failure.ErrorCode;
import dev.notifyhub.worker.failure.FailureClassifier;
import dev.notifyhub.worker.failure.FailureClassifier.Classification;
import dev.notifyhub.worker.messaging.DeliveryRoutingPublisher;
import dev.notifyhub.worker.messaging.DeliveryRoutingPublisher.PublishOutcome;
import dev.notifyhub.worker.metrics.DeliveryMetrics;
import dev.notifyhub.worker.processing.RetryPolicy.RetryDecision;
import dev.notifyhub.worker.resilience.ChannelGateway;
import dev.notifyhub.worker.template.TemplateCatalog;
import dev.notifyhub.worker.template.TemplateDocument;
import dev.notifyhub.worker.template.TemplateRenderer;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Service;

/**
 * Processa uma mensagem de entrega: claim → template → renderização → adapter → resultado.
 *
 * <p>Regras da Fase 2:
 * <ul>
 *   <li>mensagem de outro canal na fila → {@link AmqpRejectAndDontRequeueException} (DLQ);</li>
 *   <li>claim vazio (duplicada ou obsoleta) → retorno normal, a mensagem é confirmada sem envio;</li>
 *   <li>falha depois do claim (template, renderização ou adapter) → {@link FailureClassifier}; toda
 *       falha grava exatamente uma tentativa, com {@code durationMs = 0} se o adapter não foi
 *       chamado (D-14);</li>
 *   <li>falha permanente ou desconhecida (UNKNOWN, D-13) → FAILED atômico, sem gastar retries, e
 *       cópia confirmada na DLQ do canal com
 *       {@code x-failure-class}; só então o retorno normal confirma a original (D-10). Se a cópia
 *       não for confirmada, {@link AmqpRejectAndDontRequeueException} estaciona a original na DLQ
 *       pela DLX da fila principal; nunca há requeue;</li>
 *   <li>falha transitória com níveis restantes → RETRYING sem mexer em {@code retryCount} e token
 *       {@code h+1} confirmado na fila de delay do próximo nível ({@link RetryPolicy}, D-11); escada
 *       esgotada ou republicação falha → DEAD_LETTERED com cópia TRANSIENT na DLQ; nunca FAILED;</li>
 *   <li>falha do Mongo no claim ou no update de resultado não passa pelo classificador: sobe para o
 *       container (reject → DLQ) e o documento continua recuperável.</li>
 * </ul>
 */
@Service
public class DeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryProcessor.class);

    private final NotificationStateService state;
    private final TemplateCatalog catalog;
    private final TemplateRenderer renderer;
    private final ChannelGateway gateway;
    private final FailureClassifier classifier;
    private final DeliveryRoutingPublisher publisher;
    private final TopologyProperties topology;
    private final DeliveryMetrics metrics;
    private final AtomicBoolean firstMessage = new AtomicBoolean(true);

    /**
     * @param gateway  envio pelo canal, já protegido por bulkhead e circuit breaker (RES-01, RES-02)
     * @param topology níveis da escada de delay; os mesmos {@code Duration} que declararam as filas
     * @param metrics  contadores de resultado do D-15, incrementados só quando o resultado é real
     */
    public DeliveryProcessor(NotificationStateService state, TemplateCatalog catalog,
                             TemplateRenderer renderer, ChannelGateway gateway,
                             FailureClassifier classifier, DeliveryRoutingPublisher publisher,
                             TopologyProperties topology, DeliveryMetrics metrics) {
        this.state = state;
        this.catalog = catalog;
        this.renderer = renderer;
        this.gateway = gateway;
        this.classifier = classifier;
        this.publisher = publisher;
        this.topology = topology;
        this.metrics = metrics;
    }

    /**
     * @param listenerChannel canal da fila que recebeu a mensagem
     * @param retryCount      nível de retry lido do header (0 = fila principal)
     * @param redelivered     flag {@code redelivered} da entrega AMQP: permite retomar uma
     *                        notificação presa em PROCESSING por um worker derrubado (D-09)
     */
    public void process(Channel listenerChannel, NotificationMessage message, int retryCount,
                        boolean redelivered) {
        logThreadOnce();
        if (message == null || message.notificationId() == null || message.notificationId().isBlank()) {
            throw new AmqpRejectAndDontRequeueException("mensagem sem notificationId na fila "
                    + listenerChannel.queue());
        }
        if (message.channel() != listenerChannel) {
            throw new AmqpRejectAndDontRequeueException("mensagem do canal " + message.channel()
                    + " na fila " + listenerChannel.queue() + " id=" + message.notificationId());
        }

        String id = message.notificationId();
        Optional<NotificationDocument> claimed = state.claim(id, retryCount, redelivered);
        if (claimed.isEmpty()) {
            log.info("mensagem duplicada/obsoleta descartada id={} retryCount={}", id, retryCount);
            return;
        }
        if (redelivered) {
            log.info("reentrega retomada id={} retryCount={}", id, retryCount);
        }
        NotificationDocument doc = claimed.get();
        int attemptNumber = doc.attemptCount() + 1;
        Instant startedAt = Instant.now();
        boolean adapterCalled = false;
        long adapterStart = 0L;

        RenderedContent rendered;
        long durationMs;
        try {
            TemplateDocument template = catalog.require(doc.templateCode());
            rendered = renderer.render(template, doc.channel(), doc.variables(), id);

            startedAt = Instant.now();
            adapterStart = System.nanoTime();
            adapterCalled = true;
            // recusa do CB/bulkhead sai daqui em microssegundos e cai no ramo TRANSIENT (D-05)
            gateway.send(doc.channel(), doc.recipient(), rendered, new AttemptContext(retryCount, attemptNumber));
            durationMs = elapsedMs(adapterStart);
        } catch (RuntimeException e) {
            // nunca Throwable/Error: um OutOfMemoryError não pode virar FAILED
            long failedDurationMs = adapterCalled ? elapsedMs(adapterStart) : 0L;
            onFailure(e, message, doc, retryCount, attemptNumber, startedAt, failedDurationMs);
            return;
        }

        // fora do try: falha do Mongo no update de resultado não é classificada (sobe → DLQ)
        AttemptEntry attempt = new AttemptEntry(attemptNumber, retryCount, startedAt, durationMs,
                AttemptOutcome.SUCCESS, null, null, null, Thread.currentThread().toString());
        if (state.markSent(id, retryCount, rendered, attempt)) {
            log.info("Notificação entregue id={} canal={} destinatario={} tentativa={} duracaoMs={}",
                    id, doc.channel(), RecipientRules.mask(doc.channel(), doc.recipient()),
                    attempt.number(), durationMs);
        }
    }

    private void onFailure(RuntimeException e, NotificationMessage message, NotificationDocument doc,
                           int retryCount, int attemptNumber, Instant startedAt, long durationMs) {
        Classification c = classifier.classify(e);
        AttemptEntry attempt = new AttemptEntry(attemptNumber, retryCount, startedAt, durationMs,
                c.outcome(), c.errorCode().name(), c.message(), c.failureClass(),
                Thread.currentThread().toString());
        LastError lastError = new LastError(c.errorCode().name(), c.message(), c.failureClass(), Instant.now());

        switch (c.failureClass()) {
            case TRANSIENT -> onTransientFailure(message, doc, retryCount, attempt, lastError);
            case PERMANENT, UNKNOWN -> onTerminalFailure(message, doc, retryCount, attempt, lastError, c);
        }
    }

    /**
     * Ramo transitório (D-11, DLV-04, DLV-05). Uma falha transitória nunca vira FAILED: termina em
     * RETRYING ou em DEAD_LETTERED na DLQ, de onde a Fase 3 reprocessa.
     *
     * <ol>
     *   <li>Com nível restante ({@link RetryPolicy}): PROCESSING → RETRYING com a tentativa, sem mexer
     *       em {@code retryCount}; publish confirmado do token {@code h+1} na fila de delay do próximo
     *       nível; retorno normal, que confirma a original.</li>
     *   <li>Escada esgotada: PROCESSING → DEAD_LETTERED com a tentativa e cópia TRANSIENT na DLQ.</li>
     *   <li>Republicação negada, devolvida, sem confirm ou com exceção: RETRYING → DEAD_LETTERED com
     *       {@code lastError} REPUBLISH_FAILED, sem tentativa extra (D-17), e cópia TRANSIENT na DLQ.</li>
     * </ol>
     *
     * <p>Fence perdido em qualquer resultado: nada é publicado e a mensagem é confirmada (outro worker
     * é dono do documento). Cópia para a DLQ não confirmada: {@link AmqpRejectAndDontRequeueException}
     * estaciona a original na DLQ pela DLX da fila principal. Nenhum caminho confirma a mensagem com o
     * documento ainda em PROCESSING.
     *
     * <p><b>Pior janela aceita (RESEARCH Armadilha 12, Pergunta em aberto 7):</b> se a republicação
     * falhar e o canal morrer depois do RETRYING → DEAD_LETTERED, mas antes da cópia ou do reject, a
     * original volta com {@code redelivered=true}, não casa no claim (DEAD_LETTERED não é
     * reivindicável) e é confirmada. O documento fica DEAD_LETTERED, visível no GET com
     * {@code lastError} REPUBLISH_FAILED, mas sem mensagem na DLQ. Exige duas falhas seguidas e não
     * é silencioso; não é tratado nesta fase.
     */
    private void onTransientFailure(NotificationMessage message, NotificationDocument doc, int retryCount,
                                    AttemptEntry attempt, LastError lastError) {
        String id = message.notificationId();
        RetryDecision decision = RetryPolicy.decide(retryCount, topology.retryLevels());
        if (decision.exhausted()) {
            onLadderExhausted(message, retryCount, attempt, lastError);
            return;
        }
        if (!state.markRetrying(id, retryCount, attempt, lastError)) {
            // outro worker é dono do documento: confirma sem publicar
            log.warn("RETRYING não gravado (fence perdido), retry não publicado id={} retryCount={}",
                    id, retryCount);
            return;
        }
        String levelLabel = TtlLabel.of(decision.nextLevel());
        PublishOutcome outcome = publisher.publishToRetry(message.channel(), message,
                decision.nextRetryCount(), decision.nextLevel());
        if (outcome.ok()) {
            log.info("Notificação RETRYING id={} canal={} nivel={} tentativa={}",
                    id, doc.channel(), levelLabel, attempt.number());
            return;
        }
        onRepublishFailure(message, retryCount, levelLabel, outcome);
    }

    /** Escada esgotada (h ≥ N): DEAD_LETTERED com a última tentativa e cópia TRANSIENT na DLQ. */
    private void onLadderExhausted(NotificationMessage message, int retryCount, AttemptEntry attempt,
                                   LastError lastError) {
        String id = message.notificationId();
        if (!state.markDeadLettered(id, retryCount, attempt, lastError)) {
            log.warn("DEAD_LETTERED não gravado (fence perdido), cópia na DLQ não publicada id={} retryCount={}",
                    id, retryCount);
            return;
        }
        copyToDlqOrReject(message, FailureClass.TRANSIENT, retryCount);
        log.warn("Notificação DEAD_LETTERED id={} canal={} tentativas={} — cópia na DLQ",
                id, message.channel(), attempt.number());
    }

    /** Republicação falha (D-11): RETRYING → DEAD_LETTERED só com o lastError e cópia TRANSIENT na DLQ. */
    private void onRepublishFailure(NotificationMessage message, int retryCount, String levelLabel,
                                    PublishOutcome outcome) {
        String id = message.notificationId();
        // mensagem curta e sem PII: só o rótulo do nível e o motivo do broker
        LastError republishError = new LastError(ErrorCode.REPUBLISH_FAILED.name(),
                "republicação para retry." + levelLabel + " falhou: " + outcome.reason(),
                FailureClass.TRANSIENT, Instant.now());
        if (!state.markDeadLetteredAfterRepublishFailure(id, retryCount, republishError)) {
            log.warn("DEAD_LETTERED após republicação falha não gravado (fence perdido) id={} retryCount={}",
                    id, retryCount);
            return;
        }
        copyToDlqOrReject(message, FailureClass.TRANSIENT, retryCount);
        log.warn("Notificação DEAD_LETTERED id={} canal={} — republicação para retry.{} falhou: {}",
                id, message.channel(), levelLabel, outcome.reason());
    }

    /** FAILED atômico e, se o fence foi ganho, cópia confirmada na DLQ (D-10). */
    private void onTerminalFailure(NotificationMessage message, NotificationDocument doc, int retryCount,
                                   AttemptEntry attempt, LastError lastError, Classification c) {
        String id = message.notificationId();
        if (!state.markFailed(id, retryCount, attempt, lastError)) {
            // outro worker é dono do documento: confirma sem publicar na DLQ
            log.warn("FAILED não gravado (fence perdido), cópia na DLQ não publicada id={} retryCount={}",
                    id, retryCount);
            return;
        }
        copyToDlqOrReject(message, c.failureClass(), retryCount);
        log.warn("Notificação FAILED id={} canal={} destinatario={} codigo={} — cópia na DLQ",
                id, doc.channel(), RecipientRules.mask(doc.channel(), doc.recipient()), c.errorCode());
    }

    /**
     * Cópia confirmada na DLQ do canal com {@code x-failure-class}; se não for confirmada, reject sem
     * requeue leva a original para a DLQ pela DLX da fila principal (D-10).
     */
    private void copyToDlqOrReject(NotificationMessage message, FailureClass failureClass, int retryCount) {
        PublishOutcome outcome = publisher.publishToDlq(message.channel(), message, failureClass, retryCount);
        if (!outcome.ok()) {
            throw new AmqpRejectAndDontRequeueException("cópia para a DLQ não confirmada id="
                    + message.notificationId() + " motivo=" + outcome.reason());
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** Prova de virtual thread no log (WRK-05): só na primeira mensagem do processo. */
    private void logThreadOnce() {
        if (firstMessage.compareAndSet(true, false)) {
            Thread current = Thread.currentThread();
            log.info("Primeira mensagem processada na thread {} isVirtual={}", current, current.isVirtual());
        }
    }
}
