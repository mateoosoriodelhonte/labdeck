package io.labdeck.docker;

import io.labdeck.lab.LabRecord;
import java.util.List;

public record DockerStartResult(
        LabRecord lab, String networkId, List<String> volumeIds, List<DockerContainerView> containers) {
    public DockerStartResult {
        if (lab == null || networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("The Docker start result is not valid.");
        }
        volumeIds = List.copyOf(volumeIds);
        containers = List.copyOf(containers);
    }
}
