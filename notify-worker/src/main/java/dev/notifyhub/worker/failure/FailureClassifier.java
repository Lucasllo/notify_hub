package dev.notifyhub.worker.failure;

import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.samskivert.mustache.MustacheException;
import dev.notifyhub.common.domain.AttemptOutcome;
import dev.notifyhub.common.domain.FailureClass;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

/**
 * Tabela ordenada exceção → ({@link FailureClass}, {@link ErrorCode}, mensagem) (DLV-03, D-13). Sem
 * estado. A ordem importa: subclasses antes das superclasses.
 *
 * <ol>
 *   <li>{@link CallNotPermittedException} (circuit breaker do canal aberto) → TRANSIENT/CIRCUIT_OPEN e
 *       {@link BulkheadFullException} (bulkhead do canal cheio) → TRANSIENT/BULKHEAD_FULL: a recusa
 *       consome um nível da escada como qualquer transitória e nunca vira FAILED (D-05);</li>
 *   <li>{@link DeliveryFailureException} (transitória ou permanente) → classe, código e mensagem da
 *       própria exceção;</li>
 *   <li>{@link MustacheException.Context} (variável ausente ou nula) → PERMANENT/MISSING_VARIABLE;</li>
 *   <li>demais {@link MustacheException} (ex.: template que não compila) → PERMANENT/TEMPLATE_INVALID;</li>
 *   <li>{@link DataAccessResourceFailureException}, {@link TransientDataAccessException} ou, na cadeia
 *       de causas, {@link MongoTimeoutException}/{@link MongoSocketException}/
 *       {@link MongoExecutionTimeoutException} → TRANSIENT/STORE_UNAVAILABLE;</li>
 *   <li>qualquer outra → UNKNOWN/UNEXPECTED: FAILED sem retry, provável bug.</li>
 * </ol>
 *
 * <p>Nunca usa {@code getMessage()} de exceção alheia, que pode conter destinatário ou variáveis
 * (PII): as mensagens são próprias, em pt-BR, com no máximo {@value #MAX_MESSAGE} caracteres. Só a
 * mensagem de {@link DeliveryFailureException} é reaproveitada, porque o worker a escreve.
 */
@Component
public class FailureClassifier {

    /** Tamanho máximo da mensagem gravada em {@code lastError} e {@code attempts[].errorMessage}. */
    static final int MAX_MESSAGE = 300;

    public Classification classify(RuntimeException e) {
        if (e instanceof CallNotPermittedException) {
            return classification(FailureClass.TRANSIENT, ErrorCode.CIRCUIT_OPEN, "circuit breaker aberto no canal");
        }
        if (e instanceof BulkheadFullException) {
            return classification(FailureClass.TRANSIENT, ErrorCode.BULKHEAD_FULL, "bulkhead cheio no canal");
        }
        if (e instanceof DeliveryFailureException failure) {
            return classification(failure.failureClass(), failure.errorCode(), failure.getMessage());
        }
        if (e instanceof MustacheException.Context missing) {
            // só o nome da variável, nunca o valor
            return classification(FailureClass.PERMANENT, ErrorCode.MISSING_VARIABLE,
                    "variável ausente no template: " + missing.key);
        }
        if (e instanceof MustacheException) {
            return classification(FailureClass.PERMANENT, ErrorCode.TEMPLATE_INVALID, "template inválido");
        }
        if (isStoreUnavailable(e)) {
            return classification(FailureClass.TRANSIENT, ErrorCode.STORE_UNAVAILABLE,
                    "armazenamento indisponível: " + e.getClass().getSimpleName());
        }
        return classification(FailureClass.UNKNOWN, ErrorCode.UNEXPECTED,
                "erro inesperado: " + e.getClass().getSimpleName());
    }

    private static boolean isStoreUnavailable(RuntimeException e) {
        if (e instanceof DataAccessResourceFailureException || e instanceof TransientDataAccessException) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof MongoTimeoutException
                    || t instanceof MongoSocketException
                    || t instanceof MongoExecutionTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static Classification classification(FailureClass failureClass, ErrorCode code, String message) {
        return new Classification(failureClass, code, truncate(message));
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
