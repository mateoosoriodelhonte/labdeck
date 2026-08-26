package io.labdeck.docker;

import java.util.Optional;

public record DockerImagePlan(String reference, Optional<DockerImageMetadata> localImage) {
    public DockerImagePlan {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("The image reference is required.");
        }
        localImage = localImage == null ? Optional.empty() : localImage;
    }

    public boolean needsDownload() {
        return localImage.isEmpty();
    }
}
