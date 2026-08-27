package io.labdeck.docker;

import java.util.Objects;

public final class DockerEngineCapabilityException extends RuntimeException {

    public enum Reason {
        UNAVAILABLE,
        VERSION_UNSUPPORTED,
        RESOURCE_LIMITS_UNSUPPORTED
    }

    private final Reason reason;

    public DockerEngineCapabilityException(Reason reason) {
        super(message(reason));
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    private static String message(Reason reason) {
        return switch (Objects.requireNonNull(reason, "reason")) {
            case UNAVAILABLE ->
                "Docker is not available. Install or start Docker, then retry.";
            case VERSION_UNSUPPORTED ->
                "LabDeck needs Docker Engine 28 or newer for safe localhost port publishing.";
            case RESOURCE_LIMITS_UNSUPPORTED ->
                "Docker does not support the memory, swap, and CPU limits that LabDeck requires.";
        };
    }
}
