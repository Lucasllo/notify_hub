package dev.notifyhub.api.application;

import dev.notifyhub.api.web.ProblemTypes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** {@code GET /notifications/{id}} com id inexistente: 404 Problem Detail. */
public class NotificationNotFoundException extends ErrorResponseException {

    public NotificationNotFoundException(String id) {
        super(HttpStatus.NOT_FOUND, problem(id), null);
    }

    private static ProblemDetail problem(String id) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(ProblemTypes.NOT_FOUND);
        problem.setTitle("Notificação não encontrada");
        problem.setDetail("Não existe notificação com id " + id);
        return problem;
    }
}
