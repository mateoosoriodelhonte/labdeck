package io.labdeck.lab;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public record LabRuntimeFailure(
        String labId,
        long labRevision,
        LabFailureCode code,
        Optional<String> service,
        Instant occurredAt,
        boolean cleanupIncomplete) {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final Pattern SERVICE = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    public LabRuntimeFailure {
        if (labId == null || !ID.matcher(labId).matches() || labRevision < 0) {
            throw new IllegalArgumentException("The failed lab identity is not valid.");
        }
        Objects.requireNonNull(code, "code");
        service = service == null ? Optional.empty() : service;
        if (service.isPresent() && !SERVICE.matcher(service.orElseThrow()).matches()) {
            throw new IllegalArgumentException("The failed service identity is not valid.");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (occurredAt.isBefore(Instant.EPOCH)) {
            throw new IllegalArgumentException("The failure time is not valid.");
        }
        occurredAt.toEpochMilli();
    }

    public boolean retryable() {
        return switch (code) {
            case DOCKER_UNAVAILABLE, HOST_PORT_IN_USE, CONTAINER_START_FAILED,
                    CONTAINER_EXITED, HEALTHCHECK_UNHEALTHY, STARTUP_TIMEOUT,
                    CLEANUP_INCOMPLETE -> true;
            case OWNERSHIP_MISMATCH -> false;
        };
    }

    public String safeMessage() {
        String prefix = service.map(value -> "Service '" + value + "': ").orElse("");
        String action = switch (code) {
            case DOCKER_UNAVAILABLE -> "LabDeck could not inspect the local Docker engine. Start Docker and retry.";
            case HOST_PORT_IN_USE -> "the fixed local port is in use. Free it or use a dynamic host port.";
            case CONTAINER_START_FAILED -> "the container could not start. Check its command and Docker storage.";
            case CONTAINER_EXITED -> "the container exited unexpectedly. Check its command and logs.";
            case HEALTHCHECK_UNHEALTHY -> "the Docker health check failed. Check its health command and logs.";
            case STARTUP_TIMEOUT -> "startup reached its health deadline. Check the blocking services and logs.";
            case OWNERSHIP_MISMATCH -> "a Docker resource no longer matches LabDeck's ownership record.";
            case CLEANUP_INCOMPLETE -> "some owned resources could not be cleaned. Start Docker and stop the lab again.";
        };
        return prefix + action + (cleanupIncomplete && code != LabFailureCode.CLEANUP_INCOMPLETE
                ? " Some owned resources still need an explicit stop retry." : "");
    }

    @Override
    public String toString() {
        return "LabRuntimeFailure[labId=" + labId
                + ", labRevision=" + labRevision
                + ", code=" + code
                + ", service=" + service
                + ", occurredAt=" + occurredAt
                + ", cleanupIncomplete=" + cleanupIncomplete + "]";
    }
}
