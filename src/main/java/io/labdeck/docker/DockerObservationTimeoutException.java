package io.labdeck.docker;

public final class DockerObservationTimeoutException extends RuntimeException {

    public DockerObservationTimeoutException() {
        super("Docker observation exceeded its safe time limit.");
    }
}
