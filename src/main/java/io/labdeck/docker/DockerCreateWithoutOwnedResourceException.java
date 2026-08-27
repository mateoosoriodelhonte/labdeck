package io.labdeck.docker;

final class DockerCreateWithoutOwnedResourceException extends IllegalStateException {

    DockerCreateWithoutOwnedResourceException(String message, RuntimeException cause) {
        super(message, cause);
    }
}
