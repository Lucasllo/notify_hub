package dev.notifyhub.worker.processing;

import dev.notifyhub.common.domain.AttemptOutcome;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.RecipientRules;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.persistence.AttemptEntry;
import dev.notifyhub.common.persistence.LastError;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.common.persistence.RenderedContent;
import dev.notifyhub.worker.channel.ChannelAdapter;
import dev.notifyhub.worker.failure.FailureClassifier;
import dev.notifyhub.worker.failure.FailureClassifier.Classification;
import dev.notifyhub.worker.messaging.DeliveryRoutingPublisher;
import dev.notifyhub.worker.messaging.DeliveryRoutingPublisher.PublishOutcome;
import dev.notifyhub.worker.template.TemplateCatalog;
import dev.notifyhub.worker.template.TemplateDocument;
import dev.notifyhub.worker.template.TemplateRenderer;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 *   <li>falha transitória → por enquanto relança a exceção original (reject → DLQ); a escada de
 *       delay é do plano 02-02;</li>
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
    private final Map<Channel, ChannelAdapter> adapters;
    private final FailureClassifier classifier;
    private final DeliveryRoutingPublisher publisher;
    private final AtomicBoolean firstMessage = new AtomicBoolean(true);

    /**
     * @throws IllegalStateException se faltar adapter para algum canal ou houver dois para o mesmo
     */
    public DeliveryProcessor(NotificationStateService state, TemplateCatalog catalog,
                             TemplateRenderer renderer, List<ChannelAdapter> adapters,
                             FailureClassifier classifier, DeliveryRoutingPublisher publisher) {
        this.state = state;
        this.catalog = catalog;
        this.renderer = renderer;
        this.adapters = indexByChannel(adapters);
        this.classifier = classifier;
        this.publisher = publisher;
    }

    private static Map<Channel, ChannelAdapter> indexByChannel(List<ChannelAdapter> adapters) {
        Map<Channel, ChannelAdapter> byChannel = new EnumMap<>(Channel.class);
        for (ChannelAdapter adapter : adapters) {
            if (byChannel.putIfAbsent(adapter.channel(), adapter) != null) {
                throw new IllegalStateException("mais de um adapter para o canal " + adapter.channel());
            }
        }
        Set<Channel> missing = EnumSet.allOf(Channel.class);
        missing.removeAll(byChannel.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("canais sem adapter: " + missing);
        }
        return byChannel;
    }

    /**
     * @param listenerChannel canal da fila que recebeu a mensagem
     * @param retryCount      nível de retry lido do header (0 = fila principal)
     */
    public void process(Channel listenerChannel, NotificationMessage message, int retryCount) {
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
        Optional<NotificationDocument> claimed = state.claim(id, retryCount);
        if (claimed.isEmpty()) {
            log.info("mensagem duplicada/obsoleta descartada id={} retryCount={}", id, retryCount);
            return;
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
            ChannelAdapter adapter = adapters.get(doc.channel());

            startedAt = Instant.now();
            adapterStart = System.nanoTime();
            adapterCalled = true;
            adapter.send(doc.recipient(), rendered);
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
            // a escada de delay é do plano 02-02; até lá a transitória rejeita para a DLQ como na Fase 1
            case TRANSIENT -> throw e;
            case PERMANENT, UNKNOWN -> onTerminalFailure(message, doc, retryCount, attempt, lastError, c);
        }
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
        PublishOutcome outcome = publisher.publishToDlq(message.channel(), message, c.failureClass(), retryCount);
        if (!outcome.ok()) {
            throw new AmqpRejectAndDontRequeueException("cópia para a DLQ não confirmada id=" + id
                    + " motivo=" + outcome.reason());
        }
        log.warn("Notificação FAILED id={} canal={} destinatario={} codigo={} — cópia na DLQ",
                id, doc.channel(), RecipientRules.mask(doc.channel(), doc.recipient()), c.errorCode());
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
