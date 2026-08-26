package io.labdeck.docker;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public record DockerResourceRecord(
        String ownershipToken,
        LabOwnership ownership,
        DockerResourceType type,
        String logicalName,
        Optional<String> engineId,
        DockerResourceState state,
        Instant createdAt,
        Instant updatedAt) {

    private static final Pattern LOGICAL_NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern OWNERSHIP_TOKEN = Pattern.compile("[a-f0-9]{32}");

    public DockerResourceRecord {
        requireOwnershipToken(ownershipToken);
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(type, "type");
        requireLogicalName(logicalName);
        engineId = engineId == null ? Optional.empty() : engineId;
        engineId.ifPresent(DockerResourceRecord::requireEngineId);
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (createdAt.isBefore(Instant.EPOCH) || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("The Docker resource timestamps are not valid.");
        }
        requireEpochMilliseconds(createdAt);
        requireEpochMilliseconds(updatedAt);
        if (state == DockerResourceState.ACTIVE && engineId.isEmpty()) {
            throw new IllegalArgumentException("An active Docker resource needs an engine ID.");
        }
        if (state == DockerResourceState.RESERVED && engineId.isPresent()) {
            throw new IllegalArgumentException("A reserved Docker resource cannot have an engine ID.");
        }
    }

    public static DockerResourceRecord reserved(
            String token,
            LabOwnership ownership,
            DockerResourceType type,
            String logicalName,
            Instant now) {
        return new DockerResourceRecord(
                token, ownership, type, logicalName, Optional.empty(), DockerResourceState.RESERVED, now, now);
    }

    public DockerResourceRecord activate(String id, Instant now) {
        if (state != DockerResourceState.RESERVED) {
            throw new IllegalStateException("Only a reserved Docker resource can be activated.");
        }
        return new DockerResourceRecord(
                ownershipToken, ownership, type, logicalName, Optional.of(id),
                DockerResourceState.ACTIVE, createdAt, now);
    }

    public Map<String, String> labels() {
        return ownership.labels(type, logicalName, ownershipToken);
    }

    public boolean hasExactLabels(Map<String, String> actual) {
        return actual != null && labels().entrySet().stream()
                .allMatch(entry -> Objects.equals(entry.getValue(), actual.get(entry.getKey())));
    }

    @Override
    public String toString() {
        return "DockerResourceRecord[ownershipToken=<redacted>"
                + ", ownership=" + ownership
                + ", type=" + type
                + ", logicalName=" + logicalName
                + ", engineId=" + engineId
                + ", state=" + state
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt + "]";
    }

    public static void requireLogicalName(String logicalName) {
        if (logicalName == null || !LOGICAL_NAME.matcher(logicalName).matches()) {
            throw new IllegalArgumentException("The Docker resource logical name is not valid.");
        }
    }

    public static void requireOwnershipToken(String ownershipToken) {
        if (ownershipToken == null || !OWNERSHIP_TOKEN.matcher(ownershipToken).matches()) {
            throw new IllegalArgumentException("The Docker ownership token is not valid.");
        }
    }

    private static void requireEngineId(String engineId) {
        if (engineId.isBlank() || engineId.length() > 255 || !engineId.equals(engineId.strip())) {
            throw new IllegalArgumentException("The Docker engine ID is not valid.");
        }
    }

    private static void requireEpochMilliseconds(Instant value) {
        try {
            value.toEpochMilli();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("The Docker resource timestamp is outside the storage range.", exception);
        }
    }
}
