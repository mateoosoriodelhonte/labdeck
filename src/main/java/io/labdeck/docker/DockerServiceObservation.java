package io.labdeck.docker;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record DockerServiceObservation(
        DockerContainerView container,
        Optional<String> imageReference,
        DockerContainerMetrics metrics,
        OptionalLong imageSizeBytes,
        OptionalLong writableLayerBytes,
        java.util.List<DockerVolumeMountObservation> volumeMounts) {

    public DockerServiceObservation {
        Objects.requireNonNull(container, "container");
        imageReference = imageReference == null ? Optional.empty() : imageReference;
        metrics = metrics == null ? DockerContainerMetrics.UNAVAILABLE : metrics;
        imageSizeBytes = imageSizeBytes == null ? OptionalLong.empty() : imageSizeBytes;
        writableLayerBytes = writableLayerBytes == null ? OptionalLong.empty() : writableLayerBytes;
        volumeMounts = volumeMounts == null ? java.util.List.of() : java.util.List.copyOf(volumeMounts);
        requireNonNegative(imageSizeBytes, "image size");
        requireNonNegative(writableLayerBytes, "writable layer size");
    }

    public DockerServiceObservation(
            DockerContainerView container,
            Optional<String> imageReference,
            DockerContainerMetrics metrics,
            OptionalLong imageSizeBytes,
            OptionalLong writableLayerBytes) {
        this(container, imageReference, metrics, imageSizeBytes, writableLayerBytes, java.util.List.of());
    }

    private static void requireNonNegative(OptionalLong value, String name) {
        if (value.isPresent() && value.orElseThrow() < 0) {
            throw new IllegalArgumentException("The Docker " + name + " is not valid.");
        }
    }
}
