package io.labdeck.docker;

import io.labdeck.lab.LabRecord;
import java.util.List;
import java.util.Objects;

public record DockerObservabilitySnapshot(
        LabRecord lab,
        List<DockerServiceObservation> services,
        List<DockerVolumeObservation> volumes,
        boolean networkPresent) {

    public DockerObservabilitySnapshot {
        Objects.requireNonNull(lab, "lab");
        services = List.copyOf(services);
        volumes = List.copyOf(volumes);
    }
}
