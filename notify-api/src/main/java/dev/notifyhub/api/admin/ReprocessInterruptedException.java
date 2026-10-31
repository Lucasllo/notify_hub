package dev.notifyhub.api.admin;

import dev.notifyhub.api.admin.DlqReprocessService.FailureReason;
import dev.notifyhub.api.admin.DlqReprocessService.Report;
import dev.notifyhub.api.web.ProblemTypes;
import dev.notifyhub.common.domain.Channel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * O reprocessamento da DLQ parou na primeira falha (D-03): 503 com as contagens parciais e um motivo
 * do conjunto fechado {@link FailureReason}. O corpo nunca carrega texto do broker nem mensagem de
 * exceção (T-02-01); a mensagem que falhou já voltou para a DLQ com nack e requeue.
 */
public class ReprocessInterruptedException extends ErrorResponseException {

    private final transient Report report;
    private final FailureReason reason;

    public ReprocessInterruptedException(Channel channel, Report report, FailureReason reason) {
        super(HttpStatus.SERVICE_UNAVAILABLE, problem(channel, report, reason), null);
        this.report = report;
        this.reason = reason;
    }

    private static ProblemDetail problem(Channel channel, Report report, FailureReason reason) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setType(ProblemTypes.REPROCESS_INTERRUPTED);
        problem.setTitle("Reprocessamento interrompido");
        problem.setDetail("O reprocessamento da DLQ do canal " + channel.id() + " parou na primeira falha ("
                + reason.name() + "). A mensagem que falhou voltou para a DLQ.");
        problem.setProperty("requested", report.requested());
        problem.setProperty("reprocessed", report.reprocessed());
        problem.setProperty("skipped", report.skipped());
        problem.setProperty("discarded", report.discarded());
        problem.setProperty("reason", reason.name());
        return problem;
    }

    /** Contagens acumuladas até a parada. */
    public Report getReport() {
        return report;
    }

    /** Motivo da parada, do conjunto fechado. */
    public FailureReason getReason() {
        return reason;
    }
}
