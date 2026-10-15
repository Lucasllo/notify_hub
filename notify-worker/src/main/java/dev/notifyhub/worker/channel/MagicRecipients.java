package dev.notifyhub.worker.channel;

import dev.notifyhub.common.domain.Channel;
import java.util.Optional;

/** Destinatários mágicos (esqueleto de compilação; o parser vem no GREEN). */
public final class MagicRecipients {

    public static final int FAIL_UNTIL_REPROCESS_ATTEMPTS = 4;

    private MagicRecipients() {
    }

    public static Optional<MagicBehavior> parse(Channel channel, String recipient) {
        return Optional.empty();
    }

    public enum Kind { ALWAYS_TRANSIENT, ALWAYS_PERMANENT, FAIL_N_THEN_OK, FAIL_UNTIL_REPROCESS }

    public record MagicBehavior(Kind kind, int failures) {
    }
}
