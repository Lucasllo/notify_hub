package dev.notifyhub.worker.failure;

import dev.notifyhub.common.domain.FailureClass;

/**
 * Falha de entrega que já sabe a própria classe e o próprio código de erro. O
 * {@link FailureClassifier} usa esses valores diretamente, sem adivinhar pela mensagem.
 *
 * <p>A mensagem é gravada no Mongo ({@code lastError}, {@code attempts[].errorMessage}): nunca
 * coloque nela destinatário, variáveis ou conteúdo renderizado.
 */
public abstract class DeliveryFailureException extends RuntimeException {

    private final ErrorCode errorCode;

    protected DeliveryFailureException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected DeliveryFailureException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** Código estável do conjunto fechado do D-14. */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** Classe da falha: decide entre retry e FAILED. */
    public abstract FailureClass failureClass();
}
