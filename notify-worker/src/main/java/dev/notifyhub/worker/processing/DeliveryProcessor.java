package dev.notifyhub.worker.processing;

import dev.notifyhub.common.domain.AttemptOutcome;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.RecipientRules;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.persistence.AttemptEntry;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.common.persistence.RenderedContent;
import dev.notifyhub.worker.channel.ChannelAdapter;
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
 * Processa uma mensagem de entrega: claim → template → renderização → adapter → SENT.
 *
 * <p>Regras da Fase 1:
 * <ul>
 *   <li>mensagem de outro canal na fila → {@link AmqpRejectAndDontRequeueException} (DLQ);</li>
 *   <li>claim vazio (duplicada ou obsoleta) → retorno normal, a mensagem é confirmada sem envio;</li>
 *   <li>erro de template ou do adapter sobe sem tratamento: o container rejeita sem requeue e a
 *       mensagem estaciona na DLQ do canal. O documento fica em PROCESSING; o classificador de
 *       falhas e a recuperação são da Fase 2 (DLV-03, WRK-03).</li>
 * </ul>
 */
@Service
public class DeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryProcessor.class);

    private final NotificationStateService state;
    private final TemplateCatalog catalog;
    private final TemplateRenderer renderer;
    private final Map<Channel, ChannelAdapter> adapters;
    private final AtomicBoolean firstMessage = new AtomicBoolean(true);

    /**
     * @throws IllegalStateException se faltar adapter para algum canal ou houver dois para o mesmo
     */
    public DeliveryProcessor(NotificationStateService state, TemplateCatalog catalog,
                             TemplateRenderer renderer, List<ChannelAdapter> adapters,
                             dev.notifyhub.worker.failure.FailureClassifier classifier,
                             dev.notifyhub.worker.messaging.DeliveryRoutingPublisher publisher) {
        this.state = state;
        this.catalog = catalog;
        this.renderer = renderer;
        this.adapters = indexByChannel(adapters);
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

        TemplateDocument template = catalog.require(doc.templateCode());
        RenderedContent rendered = renderer.render(template, doc.channel(), doc.variables(), id);

        Instant startedAt = Instant.now();
        long start = System.nanoTime();
        adapters.get(doc.channel()).send(doc.recipient(), rendered);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        AttemptEntry attempt = new AttemptEntry(doc.attemptCount() + 1, retryCount, startedAt, durationMs,
                AttemptOutcome.SUCCESS, null, null, null, Thread.currentThread().toString());
        if (state.markSent(id, retryCount, rendered, attempt)) {
            log.info("Notificação entregue id={} canal={} destinatario={} tentativa={} duracaoMs={}",
                    id, doc.channel(), RecipientRules.mask(doc.channel(), doc.recipient()),
                    attempt.number(), durationMs);
        }
    }

    /** Prova de virtual thread no log (WRK-05): só na primeira mensagem do processo. */
    private void logThreadOnce() {
        if (firstMessage.compareAndSet(true, false)) {
            Thread current = Thread.currentThread();
            log.info("Primeira mensagem processada na thread {} isVirtual={}", current, current.isVirtual());
        }
    }
}
