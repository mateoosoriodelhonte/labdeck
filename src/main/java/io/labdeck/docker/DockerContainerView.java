package io.labdeck.docker;

import java.util.Objects;

public record DockerContainerView(String id, String name, String image, String status, boolean running) {
    public DockerContainerView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(status, "status");
    }
}
