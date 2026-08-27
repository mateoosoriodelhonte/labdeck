package io.labdeck.docker;

public final class DockerActiveServiceNotFoundException extends RuntimeException {

    public DockerActiveServiceNotFoundException() {
        super("The selected service is not active in this lab.");
    }
}
