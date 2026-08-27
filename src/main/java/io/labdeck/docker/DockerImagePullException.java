package io.labdeck.docker;

public final class DockerImagePullException extends IllegalStateException {

    public enum Reason {
        FAILED,
        TIMED_OUT,
        INTERRUPTED
    }

    private final Reason reason;

    DockerImagePullException(Reason reason) {
        super(message(reason));
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    private static String message(Reason reason) {
        return switch (java.util.Objects.requireNonNull(reason, "reason")) {
            case FAILED -> "The image could not be downloaded. Check its name and public access, then retry.";
            case TIMED_OUT -> "The image download timed out. Check Docker and network access, then retry.";
            case INTERRUPTED -> "The image download was interrupted. Retry the download.";
        };
    }
}
