package dev.notifyhub.common.domain;

import dev.notifyhub.common.messaging.TtlLabel;
import java.time.Duration;
import java.util.Locale;

/**
 * Canal de entrega. O nome em maiúsculo ({@code EMAIL}) é a forma pública da API e do documento
 * no Mongo; filas e routing keys usam o id em minúsculo ({@code email}). Este enum é o único lugar
 * que faz essa conversão (D-05).
 */
public enum Channel {
    EMAIL,
    SMS,
    WEBHOOK;

    private final String id = name().toLowerCase(Locale.ROOT);

    /** Id em minúsculo, ex.: {@code "email"}. */
    public String id() {
        return id;
    }

    /** Routing key no exchange principal, ex.: {@code "notification.email"}. */
    public String routingKey() {
        return "notification." + id;
    }

    /** Fila principal do canal, ex.: {@code "notify.email"}. */
    public String queue() {
        return "notify." + id;
    }

    /** Dead letter queue do canal, ex.: {@code "notify.email.dlq"}. */
    public String dlq() {
        return "notify." + id + ".dlq";
    }

    /** Routing key no exchange de dead letter, ex.: {@code "email.dlq"}. */
    public String dlqKey() {
        return id + ".dlq";
    }

    /** Fila de delay de um nível de retry, ex.: {@code "notify.email.retry.5s"}. */
    public String retryQueue(Duration ttl) {
        return "notify." + id + ".retry." + TtlLabel.of(ttl);
    }

    /** Routing key no exchange de retry para um nível, ex.: {@code "email.retry.5s"}. */
    public String retryKey(Duration ttl) {
        return id + ".retry." + TtlLabel.of(ttl);
    }
}
