package dev.notifyhub.api.application;

import dev.notifyhub.api.messaging.PublishProperties;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Varredura agendada que dá saída ao PENDING que nunca teve confirm do broker (Gap 1 do
 * 03-VERIFICATION, CR-01 do 03-REVIEW). Esse estado aparece quando a JVM cai entre o insert e o
 * confirm, ou quando o MongoDB falha no {@code markPublishFailed} depois de um publish sem confirm: o
 * documento fica PENDING sem mensagem em fila nenhuma, e o worker nunca o vê.
 *
 * <p>A cada {@code notify.publish.unconfirmed-sweep.interval}, todo PENDING sem claim com
 * {@code updatedAt} mais antigo que {@code notify.publish.unconfirmed-sweep.after} vira PUBLISH_FAILED
 * com {@code lastError.code} PUBLISH_UNCONFIRMED, pelo serviço único de publish
 * ({@link NotificationRepublisher#markUnconfirmedAsPublishFailed}). Daí o operador o republica com
 * {@code POST /admin/notifications/republish-failed} (ADM-03), e ele fica visível no
 * {@code GET /notifications?status=PUBLISH_FAILED} e no {@code /stats}.
 *
 * <p>Não é o sweeper de lease descartado no D-01: não toca PROCESSING nem {@code leaseUntil}.
 *
 * <p>Um falso positivo (fila com backlog maior que {@code after}) é inofensivo: PUBLISH_FAILED continua
 * reivindicável pelo worker, que entrega a mensagem normalmente, e o {@code markSent} remove o
 * {@code lastError}.
 *
 * <p><b>Corte validado no startup.</b> {@code after} precisa ser ≥ {@value #MIN_AFTER_FACTOR}×
 * {@code notify.publish.confirm-timeout}; senão o construtor lança {@link IllegalStateException} e a API
 * não sobe. Com essa folga, a varredura nunca alcança uma publicação ainda em voo (que espera o confirm
 * por até {@code confirm-timeout} depois do insert ou do fence do republish) nem a janela do replay.
 *
 * <p><b>MongoDB fora.</b> Uma {@link DataAccessException} numa varredura vira só um WARN com o nome da
 * classe da exceção, nunca o texto (T-02-01), e a varredura devolve 0. A próxima execução agendada
 * tenta de novo, sem retry em laço.
 */
@Component
public class UnconfirmedPublishSweeper {

    private static final Logger log = LoggerFactory.getLogger(UnconfirmedPublishSweeper.class);

    /** {@code after} mínimo, em múltiplos de {@code notify.publish.confirm-timeout}. */
    static final int MIN_AFTER_FACTOR = 3;

    private final NotificationRepublisher republisher;
    private final UnconfirmedSweepProperties props;

    public UnconfirmedPublishSweeper(NotificationRepublisher republisher, UnconfirmedSweepProperties props,
                                     PublishProperties publishProps) {
        Duration minimum = publishProps.confirmTimeout().multipliedBy(MIN_AFTER_FACTOR);
        if (props.after().compareTo(minimum) < 0) {
            throw new IllegalStateException("notify.publish.unconfirmed-sweep.after (" + props.after()
                    + ") precisa ser no mínimo " + MIN_AFTER_FACTOR + "x notify.publish.confirm-timeout ("
                    + publishProps.confirmTimeout() + "), ou seja, " + minimum
                    + ", para a varredura nunca alcançar uma publicação em voo");
        }
        this.republisher = republisher;
        this.props = props;
    }

    /**
     * Uma varredura: marca PUBLISH_FAILED os PENDING sem claim com {@code updatedAt} anterior a
     * agora − {@code after}.
     *
     * @return quantos documentos foram marcados; 0 também quando o MongoDB está fora
     */
    public long sweep() {
        Instant cutoff = Instant.now().minus(props.after());
        long marked;
        try {
            marked = republisher.markUnconfirmedAsPublishFailed(cutoff);
        } catch (DataAccessException e) {
            // T-02-01: só o nome da classe, nunca o texto da exceção do driver
            log.warn("Varredura de PENDING sem confirm não executada: MongoDB indisponível ({}); tentando de novo"
                    + " na próxima execução", e.getClass().getSimpleName());
            return 0;
        }
        if (marked > 0) {
            // Sem ids nem destinatários no log (T-02-01)
            log.warn("{} notificações PENDING sem confirm há mais de {} marcadas PUBLISH_FAILED (PUBLISH_UNCONFIRMED);"
                    + " republique com POST /admin/notifications/republish-failed", marked, props.after());
        }
        return marked;
    }

    @Scheduled(fixedDelayString = "${notify.publish.unconfirmed-sweep.interval:30s}",
            initialDelayString = "${notify.publish.unconfirmed-sweep.interval:30s}")
    void scheduledSweep() {
        sweep();
    }
}
