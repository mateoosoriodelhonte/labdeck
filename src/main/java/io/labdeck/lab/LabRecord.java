package io.labdeck.lab;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record LabRecord(
        String id,
        String projectId,
        String name,
        int manifestVersion,
        Path workspace,
        LabState state,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    public LabRecord {
        if (id == null || !ID.matcher(id).matches()
                || projectId == null || !ID.matcher(projectId).matches()) {
            throw new IllegalArgumentException("The lab or project ID is not valid.");
        }
        if (name == null || name.isBlank() || !name.equals(name.strip()) || name.length() > 100) {
            throw new IllegalArgumentException("The lab name is not valid.");
        }
        if (manifestVersion != 1) {
            throw new IllegalArgumentException("Only manifest version 1 can be stored.");
        }
        Objects.requireNonNull(workspace, "workspace");
        workspace = workspace.toAbsolutePath().normalize();
        if (!workspace.isAbsolute() || workspace.getParent() == null || workspace.toString().length() > 4_096) {
            throw new IllegalArgumentException("The workspace path is not valid.");
        }
        Objects.requireNonNull(state, "state");
        if (revision < 0) {
            throw new IllegalArgumentException("The lab revision is not valid.");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (createdAt.isBefore(Instant.EPOCH) || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("The lab timestamps are not valid.");
        }
        requireEpochMilliseconds(createdAt);
        requireEpochMilliseconds(updatedAt);
    }

    public LabRecord transitionTo(LabState next, Instant transitionTime) {
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException("The lab lifecycle transition is not allowed.");
        }
        if (transitionTime == null || transitionTime.isBefore(updatedAt)) {
            throw new IllegalArgumentException("The transition time is not valid.");
        }
        return new LabRecord(
                id, projectId, name, manifestVersion, workspace, next, revision + 1, createdAt, transitionTime);
    }

    @Override
    public String toString() {
        return "LabRecord[id=" + id
                + ", projectId=" + projectId
                + ", name=" + name
                + ", manifestVersion=" + manifestVersion
                + ", workspace=<local path>"
                + ", state=" + state
                + ", revision=" + revision
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt + "]";
    }

    private static void requireEpochMilliseconds(Instant value) {
        try {
            value.toEpochMilli();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("The lab timestamp is outside the storage range.", exception);
        }
    }
}
