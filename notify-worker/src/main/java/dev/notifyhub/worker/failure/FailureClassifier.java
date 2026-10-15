package dev.notifyhub.worker.failure;

import dev.notifyhub.common.domain.AttemptOutcome;
import dev.notifyhub.common.domain.FailureClass;
import org.springframework.stereotype.Component;

/**
 * Tabela ordenada exceção → ({@link FailureClass}, {@link ErrorCode}, mensagem). Sem estado.
 *
 * <p>Regras:
 * <ol>
 *   <li>{@link DeliveryFailureException} → classe, código e mensagem da própria exceção.</li>
 * </ol>
 *
 * <p>A mensagem devolvida é gravada no Mongo e nos logs: tem no máximo {@value #MAX_MESSAGE}
 * caracteres.
 */
@Component
public class FailureClassifier {

    /** Tamanho máximo da mensagem gravada em {@code lastError} e {@code attempts[].errorMessage}. */
    static final int MAX_MESSAGE = 300;

    public Classification classify(RuntimeException e) {
        if (e instanceof DeliveryFailureException failure) {
            return new Classification(failure.failureClass(), failure.errorCode(), truncate(failure.getMessage()));
        }
        // ainda sem regra para exceções alheias: tratadas como permanentes e inesperadas
        return new Classification(FailureClass.PERMANENT, ErrorCode.UNEXPECTED,
                "erro inesperado: " + e.getClass().getSimpleName());
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE);
    }

    /**
     * Resultado da classificação.
     *
     * @param failureClass classe da falha
     * @param errorCode    código do conjunto fechado do D-14
     * @param message      mensagem própria, sem PII, com no máximo {@value #MAX_MESSAGE} caracteres
     */
    public record Classification(FailureClass failureClass, ErrorCode errorCode, String message) {

        /** Outcome gravado na tentativa. */
        public AttemptOutcome outcome() {
            return switch (failureClass) {
                case TRANSIENT -> AttemptOutcome.TRANSIENT_FAILURE;
                case PERMANENT -> AttemptOutcome.PERMANENT_FAILURE;
                case UNKNOWN -> AttemptOutcome.UNKNOWN_FAILURE;
            };
        }
    }
}
