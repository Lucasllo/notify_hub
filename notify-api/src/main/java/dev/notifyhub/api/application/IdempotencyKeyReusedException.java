package dev.notifyhub.api.application;

import dev.notifyhub.api.web.ProblemTypes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * A {@code Idempotency-Key} já foi usada com um payload diferente: 422 (D-06), sem criar documento e
 * sem publicar.
 *
 * <p>O corpo é fixo e não ecoa a chave nem o id da notificação original: a API pública não tem
 * autenticação, e a chave pode ter sido adivinhada por outro cliente.
 */
public class IdempotencyKeyReusedException extends ErrorResponseException {

    public IdempotencyKeyReusedException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(ProblemTypes.IDEMPOTENCY_KEY_REUSED);
        problem.setTitle("Idempotency-Key já usada com outro payload");
        problem.setDetail("Esta Idempotency-Key já foi usada com um payload diferente."
                + " Use uma chave nova para um pedido diferente.");
        return problem;
    }
}
