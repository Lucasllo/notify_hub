package dev.notifyhub.common.domain;

/** Classe de uma falha de entrega: transitória vai para retry, permanente vai direto para a DLQ. */
public enum FailureClass {
    TRANSIENT,
    PERMANENT
}
