package dev.notifyhub.api.application;

import dev.notifyhub.api.web.ProblemTypes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * O lote do {@code POST /admin/notifications/republish-failed} parou na primeira falha (padrão do
 * D-03): 503 com as contagens parciais e o motivo. As notificações que faltaram continuam
 * PUBLISH_FAILED, prontas para a próxima chamada.
 *
 * <p>{@code reason} vem só de {@link dev.notifyhub.api.messaging.PublishResult#errorCode()}
 * (PUBLISH_NACK, PUBLISH_RETURNED, PUBLISH_TIMEOUT, BROKER_UNAVAILABLE) ou da constante
 * {@link NotificationRepublisher#MONGO_UNAVAILABLE}, nunca de texto livre do broker ou de mensagem de
 * exceção (T-02-01).
 */
public class RepublishInterruptedException extends ErrorResponseException {

    private final transient NotificationRepublisher.RepublishReport report;
    private final String reason;

    public RepublishInterruptedException(NotificationRepublisher.RepublishReport partial, String reason) {
        super(HttpStatus.SERVICE_UNAVAILABLE, problem(partial, reason), null);
        this.report = partial;
        this.reason = reason;
    }

    private static ProblemDetail problem(NotificationRepublisher.RepublishReport partial, String reason) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setType(ProblemTypes.REPUBLISH_INTERRUPTED);
        problem.setTitle("Republicação interrompida");
        problem.setDetail("A republicação das notificações PUBLISH_FAILED parou na primeira falha (" + reason
                + "). As que faltaram continuam PUBLISH_FAILED.");
        problem.setProperty("requested", partial.requested());
        problem.setProperty("republished", partial.republished());
        problem.setProperty("reason", reason);
        return problem;
    }

    /** Contagens acumuladas até a parada. */
    public NotificationRepublisher.RepublishReport getReport() {
        return report;
    }

    /** Motivo da parada, do conjunto fechado. */
    public String getReason() {
        return reason;
    }
}
