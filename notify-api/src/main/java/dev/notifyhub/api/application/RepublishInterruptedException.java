package dev.notifyhub.api.application;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Esqueleto do RED (03-05): 503 sem corpo. */
public class RepublishInterruptedException extends ErrorResponseException {

    private final transient NotificationRepublisher.RepublishReport report;
    private final String reason;

    public RepublishInterruptedException(NotificationRepublisher.RepublishReport partial, String reason) {
        super(HttpStatus.SERVICE_UNAVAILABLE, ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE), null);
        this.report = partial;
        this.reason = reason;
    }

    public NotificationRepublisher.RepublishReport getReport() {
        return report;
    }

    public String getReason() {
        return reason;
    }
}
