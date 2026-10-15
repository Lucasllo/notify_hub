package dev.notifyhub.worker.channel;

import dev.notifyhub.worker.failure.ErrorCode;
import dev.notifyhub.worker.failure.TransientDeliveryException;

/**
 * Falha simulada de envio, transitória ({@link ErrorCode#SIMULATED_TRANSIENT}). Inclui o envio
 * interrompido ({@code InterruptedException} embrulhada pelo adapter), que também vai para retry.
 */
public class SimulatedDeliveryException extends TransientDeliveryException {

    public SimulatedDeliveryException(String message) {
        super(ErrorCode.SIMULATED_TRANSIENT, message);
    }

    public SimulatedDeliveryException(String message, Throwable cause) {
        super(ErrorCode.SIMULATED_TRANSIENT, message, cause);
    }
}
