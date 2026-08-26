package io.labdeck.docker;

public final class DockerOwnershipException extends IllegalStateException {
    public DockerOwnershipException(String message) {
        super(message);
    }
}
