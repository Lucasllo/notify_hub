package dev.notifyhub.worker.channel;

/** Falha simulada de envio. A Fase 2 a classifica como transitória. */
public class SimulatedDeliveryException extends RuntimeException {

    public SimulatedDeliveryException(String message) {
        super(message);
    }

    public SimulatedDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
