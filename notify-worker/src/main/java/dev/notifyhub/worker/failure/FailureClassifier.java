package dev.notifyhub.worker.failure;

import dev.notifyhub.common.domain.AttemptOutcome;
import dev.notifyhub.common.domain.FailureClass;
import org.springframework.stereotype.Component;

/** Esqueleto (RED): a tabela de classificação ainda não existe. */
@Component
public class FailureClassifier {

    public Classification classify(RuntimeException e) {
        throw new UnsupportedOperationException("classificador pendente");
    }

    public record Classification(FailureClass failureClass, ErrorCode errorCode, String message) {

        public AttemptOutcome outcome() {
            throw new UnsupportedOperationException("classificador pendente");
        }
    }
}
