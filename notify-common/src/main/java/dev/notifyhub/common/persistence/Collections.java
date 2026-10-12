package dev.notifyhub.common.persistence;

/** Nomes das coleções do MongoDB compartilhadas entre API e worker. */
public final class Collections {

    public static final String NOTIFICATIONS = "notifications";
    public static final String TEMPLATES = "templates";

    private Collections() {
    }
}
