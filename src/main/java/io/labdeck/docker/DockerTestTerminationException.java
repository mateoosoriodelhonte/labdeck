package io.labdeck.docker;

public final class DockerTestTerminationException extends RuntimeException {

    public DockerTestTerminationException() {
        super("LabDeck could not prove that the constrained test process stopped.");
    }
}
