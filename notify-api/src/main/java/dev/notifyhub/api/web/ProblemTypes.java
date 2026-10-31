package dev.notifyhub.api.web;

import java.net.URI;

/** Valores de {@code type} dos Problem Details da API (RFC 9457 aceita URI não-HTTP). */
public final class ProblemTypes {

    public static final URI VALIDATION = URI.create("urn:notify-hub:problem:validation-error");
    public static final URI PUBLISH_FAILED = URI.create("urn:notify-hub:problem:publish-failed");
    public static final URI NOT_FOUND = URI.create("urn:notify-hub:problem:not-found");
    public static final URI REPROCESS_INTERRUPTED = URI.create("urn:notify-hub:problem:reprocess-interrupted");
    public static final URI UNAUTHORIZED = URI.create("urn:notify-hub:problem:unauthorized");

    private ProblemTypes() {
    }
}
