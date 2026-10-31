package dev.notifyhub.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.GetResponse;
import dev.notifyhub.api.application.NotificationResetService;
import dev.notifyhub.api.application.NotificationResetService.Missing;
import dev.notifyhub.api.application.NotificationResetService.Reset;
import dev.notifyhub.api.application.NotificationResetService.ResetResult;
import dev.notifyhub.api.application.NotificationResetService.Sent;
import dev.notifyhub.api.messaging.NotificationPublisher;
import dev.notifyhub.api.messaging.PublishProperties;
import dev.notifyhub.api.messaging.PublishResult;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.messaging.NotificationMessage;
import dev.notifyhub.common.persistence.NotificationDocument;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Reprocessamento da DLQ de um canal (ADM-01, D-02, D-03): move até {@code max} mensagens da DLQ de
 * volta à fila principal, uma por vez, e para na primeira falha sem perder nada.
 *
 * <pre>
 * orçamento = min(max, mensagens prontas na DLQ no início da chamada)   (DLQ inexistente → 503)
 * repete até o orçamento ou a DLQ esvaziar, num único canal:
 *   basicGet(dlq, autoAck=false)
 *   corpo ilegível, sem notificationId ou de outro canal ── cópia para o fim da DLQ → ack → skipped
 *   reset no Mongo (findAndModify)
 *     Reset ─────────── republish confirmado (notify-retry-count 0) ──── ack → reprocessed
 *     Missing / Sent ── nada a entregar ──────────────────────────────── ack → discarded
 *     qualquer outro ── cópia confirmada para o fim da DLQ ───────────── ack → skipped
 *   publish ou cópia sem ACK, Mongo fora ── basicNack(requeue=true) → para → 503 com motivo fechado
 * </pre>
 *
 * <p><b>Ack só depois do confirm.</b> Cada entrega termina em exatamente um {@code basicAck} ou um
 * {@code basicNack(tag, false, true)}, sempre no canal do {@code basicGet} (delivery tags são por
 * canal). Uma exceção inesperada também leva nack com requeue, num {@code finally} por entrega, para
 * a entrega nunca ficar pendurada num canal do cache (Armadilha 1).
 *
 * <p><b>Nunca rejeitar na DLQ.</b> A DLQ não tem dead-letter exchange: {@code basicReject} ou nack
 * sem requeue faria o broker descartar a mensagem (Armadilha 2). A mensagem que falha volta à sua
 * posição original; a que não pode voltar à fila principal ({@code skipped}) é copiada para o fim,
 * depois das prontas no início, e por isso nunca é relida na mesma chamada (Armadilha 3).
 *
 * <p><b>Corpo lido para o tipo concreto.</b> {@code objectMapper.readValue(body,
 * NotificationMessage.class)}, nunca o conversor do template nem o header {@code __TypeId__}
 * (Armadilha 4). O republish é montado a partir do documento resetado, não do corpo da DLQ.
 *
 * <p><b>Reset gravado, publish falho.</b> O documento fica PENDING com o evento REPROCESSED no
 * histórico e a mensagem volta à DLQ. Como a tabela aceita PENDING → PENDING (D-01, plano 03-03), o
 * próximo reprocess a retoma. PROCESSING com lease vigente chega aqui como {@code LeaseActive} e cai
 * no ramo padrão: cópia no fim da DLQ ({@code skipped}).
 */
@Service
public class DlqReprocessService {

    private static final Logger log = LoggerFactory.getLogger(DlqReprocessService.class);

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;
    private final NotificationResetService resetService;
    private final NotificationPublisher publisher;
    private final ObjectMapper objectMapper;
    private final PublishProperties props;

    public DlqReprocessService(RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin,
            NotificationResetService resetService, NotificationPublisher publisher, ObjectMapper objectMapper,
            PublishProperties props) {
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
        this.resetService = resetService;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /**
     * Reprocessa até {@code max} mensagens da DLQ do canal.
     *
     * @param channel canal da DLQ
     * @param max     máximo de mensagens consumidas nesta chamada (já validado pelo controller)
     * @return as contagens, com {@code requested} = {@code max}
     * @throws ReprocessInterruptedException na primeira falha, com as contagens parciais
     */
    public Report reprocess(Channel channel, int max) {
        Tally tally = new Tally(max);
        QueueInformation info;
        try {
            info = amqpAdmin.getQueueInfo(channel.dlq());
        } catch (AmqpException e) {
            throw interrupted(channel, tally, FailureReason.BROKER_UNAVAILABLE);
        }
        if (info == null) {
            throw interrupted(channel, tally, FailureReason.DLQ_UNAVAILABLE);
        }
        int ready = info.getMessageCount();
        int budget = Math.min(max, ready);
        log.info("Reprocessamento da DLQ {}: pedido={}, prontas={}, orçamento={}", channel.dlq(), max, ready, budget);
        if (budget == 0) {
            return tally.report();
        }

        try {
            rabbitTemplate.execute(dlqChannel -> {
                try {
                    drain(dlqChannel, channel, budget, tally);
                } catch (RuntimeException unexpected) {
                    // Guardada e relançada depois do execute: não vira UncategorizedAmqpException nem
                    // é confundida com broker fora. A entrega em curso já levou nack com requeue.
                    tally.unexpected = unexpected;
                }
                return null;
            });
        } catch (AmqpException e) {
            // O canal ou a conexão caiu: as entregas sem ack voltam sozinhas à DLQ quando o canal fecha
            tally.stop(FailureReason.BROKER_UNAVAILABLE);
        }

        if (tally.unexpected != null) {
            throw tally.unexpected;
        }
        if (tally.reason != null) {
            throw interrupted(channel, tally, tally.reason);
        }
        Report report = tally.report();
        log.info("Reprocessamento da DLQ {} concluído: reprocessadas={}, preservadas no fim={}, descartadas={}",
                channel.dlq(), report.reprocessed(), report.skipped(), report.discarded());
        return report;
    }

    private void drain(com.rabbitmq.client.Channel dlqChannel, Channel channel, int budget, Tally tally)
            throws IOException {
        for (int i = 0; i < budget && tally.reason == null; i++) {
            GetResponse delivery = dlqChannel.basicGet(channel.dlq(), false);
            if (delivery == null) {
                return; // a DLQ esvaziou antes do orçamento
            }
            long tag = delivery.getEnvelope().getDeliveryTag();
            boolean settled = false;
            try {
                Step step = handle(channel, delivery);
                if (step.reason() == null) {
                    dlqChannel.basicAck(tag, false);
                    settled = true;
                    tally.count(step);
                } else {
                    dlqChannel.basicNack(tag, false, true);
                    settled = true;
                    tally.stop(step.reason());
                }
            } finally {
                if (!settled) {
                    // Nunca deixar a entrega pendurada no canal do cache (Armadilha 1)
                    dlqChannel.basicNack(tag, false, true);
                }
            }
        }
    }

    private Step handle(Channel channel, GetResponse delivery) {
        String invalid = null;
        NotificationMessage message = null;
        try {
            message = objectMapper.readValue(delivery.getBody(), NotificationMessage.class);
        } catch (IOException e) {
            invalid = "corpo ilegível";
        }
        if (invalid == null && (message == null || message.notificationId() == null
                || message.notificationId().isBlank())) {
            invalid = "sem notificationId";
        } else if (invalid == null && message.channel() != channel) {
            invalid = "canal diferente do da DLQ";
        }
        if (invalid != null) {
            // Preservar, nunca descartar: a mensagem se perderia em silêncio (Pergunta em aberto 1)
            log.warn("Mensagem {} da DLQ {} com {}: preservada no fim da DLQ sem consultar o Mongo",
                    delivery.getProps().getMessageId(), channel.dlq(), invalid);
            return copyToTail(channel, delivery);
        }

        ResetResult result;
        try {
            result = resetService.resetForReprocess(message.notificationId());
        } catch (DataAccessException e) {
            return Step.failed(FailureReason.MONGO_UNAVAILABLE);
        }

        if (result instanceof Reset reset) {
            NotificationDocument doc = reset.document();
            PublishResult published = publisher.publish(
                    new NotificationMessage(doc.id(), doc.channel(), doc.templateCode(), doc.createdAt()),
                    props.confirmTimeout());
            return published.ok() ? Step.REPROCESSED : Step.failed(FailureReason.of(published));
        }
        if (result instanceof Missing || result instanceof Sent) {
            return Step.DISCARDED; // não há mais nada a entregar (D-02)
        }
        // Ramo padrão, sem switch exaustivo: um resultado novo do reset também preserva a mensagem
        return copyToTail(channel, delivery);
    }

    private Step copyToTail(Channel channel, GetResponse delivery) {
        PublishResult copied = publisher.copyToDlqTail(channel, delivery.getBody(), delivery.getProps().getMessageId(),
                delivery.getProps().getContentType(), delivery.getProps().getHeaders(), props.confirmTimeout());
        return copied.ok() ? Step.SKIPPED : Step.failed(FailureReason.of(copied));
    }

    private ReprocessInterruptedException interrupted(Channel channel, Tally tally, FailureReason reason) {
        Report report = tally.report();
        log.warn("Reprocessamento da DLQ {} interrompido ({}): reprocessadas={}, preservadas no fim={}, descartadas={}",
                channel.dlq(), reason, report.reprocessed(), report.skipped(), report.discarded());
        return new ReprocessInterruptedException(channel, report, reason);
    }

    /**
     * Contagens de uma chamada.
     *
     * @param requested   o {@code max} efetivo da chamada
     * @param reprocessed mensagens resetadas e republicadas com confirm
     * @param skipped     mensagens preservadas no fim da DLQ
     * @param discarded   mensagens retiradas sem republicar (documento SENT ou inexistente)
     */
    public record Report(int requested, int reprocessed, int skipped, int discarded) {
    }

    /**
     * Motivo da parada do reprocessamento, de um conjunto fechado. Nunca carrega texto livre do broker
     * nem mensagem de exceção (T-02-01).
     */
    public enum FailureReason {
        PUBLISH_NACK,
        PUBLISH_RETURNED,
        PUBLISH_TIMEOUT,
        BROKER_UNAVAILABLE,
        MONGO_UNAVAILABLE,
        DLQ_UNAVAILABLE;

        /** Motivo de uma publicação sem ACK; só o {@code Outcome} é lido, nunca o {@code detail}. */
        public static FailureReason of(PublishResult result) {
            return switch (result.outcome()) {
                case NACK -> PUBLISH_NACK;
                case RETURNED -> PUBLISH_RETURNED;
                case TIMEOUT -> PUBLISH_TIMEOUT;
                case BROKER_UNAVAILABLE -> BROKER_UNAVAILABLE;
                case ACK -> throw new IllegalArgumentException("publicação confirmada não tem motivo de falha");
            };
        }
    }

    /** Desfecho de uma entrega: contagem a incrementar ou motivo de parada. */
    private record Step(Kind kind, FailureReason reason) {

        static final Step REPROCESSED = new Step(Kind.REPROCESSED, null);
        static final Step SKIPPED = new Step(Kind.SKIPPED, null);
        static final Step DISCARDED = new Step(Kind.DISCARDED, null);

        static Step failed(FailureReason reason) {
            return new Step(null, reason);
        }

        enum Kind { REPROCESSED, SKIPPED, DISCARDED }
    }

    /** Contagens mutáveis, acumuladas fora do callback do {@code execute}. */
    private static final class Tally {

        private final int requested;
        private int reprocessed;
        private int skipped;
        private int discarded;
        private FailureReason reason;
        private RuntimeException unexpected;

        Tally(int requested) {
            this.requested = requested;
        }

        void count(Step step) {
            switch (step.kind()) {
                case REPROCESSED -> reprocessed++;
                case SKIPPED -> skipped++;
                case DISCARDED -> discarded++;
            }
        }

        void stop(FailureReason failure) {
            if (reason == null) {
                reason = failure;
            }
        }

        Report report() {
            return new Report(requested, reprocessed, skipped, discarded);
        }
    }
}
