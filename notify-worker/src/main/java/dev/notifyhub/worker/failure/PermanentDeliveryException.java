package dev.notifyhub.worker.failure;

import dev.notifyhub.common.domain.FailureClass;

/** Falha que não melhora com retry: a notificação vai direto para FAILED e para a DLQ. */
public class PermanentDeliveryException extends DeliveryFailureException {

    public PermanentDeliveryException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public PermanentDeliveryException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    @Override
    public FailureClass failureClass() {
        return FailureClass.PERMANENT;
    }
}
