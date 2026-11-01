package dev.notifyhub.api.application;

import dev.notifyhub.api.messaging.PublishResult;
import dev.notifyhub.api.web.ProblemTypes;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * O broker não confirmou a publicação e não há evidência de que o worker tenha reivindicado a
 * notificação: 503 com {@code Retry-After} (D-04). O documento fica PUBLISH_FAILED ou, se o MongoDB
 * falhou na marcação, PENDING até a varredura de publicações sem confirm levá-lo a PUBLISH_FAILED. Por
 * isso o detail não afirma o status gravado. O corpo cita só o id e o código do erro, nunca a mensagem
 * interna.
 */
public class PublishFailedException extends ErrorResponseException {

    private final String notificationId;
    private final transient PublishResult result;

    public PublishFailedException(String notificationId, Duration retryAfter, PublishResult result) {
        super(HttpStatus.SERVICE_UNAVAILABLE, problem(notificationId, result), null);
        this.notificationId = notificationId;
        this.result = result;
        getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.toSeconds()));
    }

    private static ProblemDetail problem(String notificationId, PublishResult result) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setType(ProblemTypes.PUBLISH_FAILED);
        problem.setTitle("Publicação não confirmada");
        problem.setDetail("O broker não confirmou a publicação da notificação " + notificationId
                + " (" + result.errorCode() + "). Tente novamente mais tarde.");
        problem.setProperty("notificationId", notificationId);
        return problem;
    }

    public String getNotificationId() {
        return notificationId;
    }

    public PublishResult getResult() {
        return result;
    }
}
