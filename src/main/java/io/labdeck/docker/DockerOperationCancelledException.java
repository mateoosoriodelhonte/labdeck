package io.labdeck.docker;

public final class DockerOperationCancelledException extends IllegalStateException {
    public DockerOperationCancelledException() {
        super("The Docker lab operation was cancelled.");
    }
}
