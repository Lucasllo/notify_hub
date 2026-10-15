package dev.notifyhub.worker.failure;

import dev.notifyhub.common.domain.FailureClass;

/** Falha que pode melhorar sozinha: a notificação percorre a escada de retry. */
public class TransientDeliveryException extends DeliveryFailureException {

    public TransientDeliveryException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public TransientDeliveryException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    @Override
    public FailureClass failureClass() {
        return FailureClass.TRANSIENT;
    }
}
