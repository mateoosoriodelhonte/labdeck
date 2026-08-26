package io.labdeck.docker;

import java.util.List;

public final class DockerImagesRequiredException extends IllegalStateException {
    private final List<String> missingImages;

    public DockerImagesRequiredException(List<String> missingImages) {
        super("The lab needs confirmed public image downloads before it can start.");
        this.missingImages = List.copyOf(missingImages);
        if (this.missingImages.isEmpty()) {
            throw new IllegalArgumentException("At least one missing image is required.");
        }
    }

    public List<String> missingImages() {
        return missingImages;
    }
}
