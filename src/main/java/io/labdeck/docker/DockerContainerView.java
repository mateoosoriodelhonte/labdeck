package io.labdeck.docker;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

public record DockerContainerView(
        String id,
        String service,
        String name,
        String image,
        String status,
        boolean running,
        OptionalInt exitCode,
        DockerHealthStatus health,
        List<DockerPortMapping> ports) {

    public DockerContainerView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(status, "status");
        exitCode = exitCode == null ? OptionalInt.empty() : exitCode;
        Objects.requireNonNull(health, "health");
        ports = List.copyOf(ports);
    }
}
