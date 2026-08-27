package io.labdeck.docker;

import io.labdeck.lab.LabRecord;
import java.util.List;
import java.util.Objects;

public record DockerServiceSnapshot(LabRecord lab, List<DockerContainerView> services) {

    public DockerServiceSnapshot {
        Objects.requireNonNull(lab, "lab");
        services = List.copyOf(services);
    }
}
