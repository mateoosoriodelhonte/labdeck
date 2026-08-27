package io.labdeck.docker;

public final class DockerStorageFullException extends IllegalStateException {

    DockerStorageFullException() {
        super("Docker storage is full. Free space in Docker storage and retry. "
                + "LabDeck did not delete or prune anything.");
    }
}
