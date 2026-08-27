package io.labdeck.docker;

import java.util.Set;

public record DockerImageMetadata(String id, long sizeBytes, Set<String> declaredVolumeTargets) {
    public DockerImageMetadata {
        if (id == null || id.isBlank() || sizeBytes < 0) {
            throw new IllegalArgumentException("The Docker image metadata is not valid.");
        }
        declaredVolumeTargets = Set.copyOf(declaredVolumeTargets);
    }
}
