package io.labdeck.docker;

public final class DockerOwnershipException extends IllegalStateException {
    public DockerOwnershipException(String message) {
        super(message);
    }

    public DockerOwnershipException(String message, Throwable cause) {
        super(message, cause);
    }
}
