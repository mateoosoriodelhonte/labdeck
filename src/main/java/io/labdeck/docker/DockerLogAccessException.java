package io.labdeck.docker;

public final class DockerLogAccessException extends RuntimeException {

    public DockerLogAccessException() {
        super("Docker could not read the selected service logs.");
    }
}
