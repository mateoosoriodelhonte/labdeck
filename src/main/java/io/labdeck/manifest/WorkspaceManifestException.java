package io.labdeck.manifest;

public final class WorkspaceManifestException extends IllegalArgumentException {

    public enum Reason {
        NOT_FOUND,
        UNSAFE_FILE,
        CHANGED_DURING_READ,
        READ_FAILED
    }

    private final Reason reason;

    WorkspaceManifestException(Reason reason, String message) {
        super(message);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
