package io.labdeck.docker;

import java.util.OptionalLong;

public record DockerVolumeObservation(
        String volume,
        OptionalLong sizeBytes,
        String measurement) {

    public DockerVolumeObservation {
        DockerResourceRecord.requireLogicalName(volume);
        sizeBytes = sizeBytes == null ? OptionalLong.empty() : sizeBytes;
        if (sizeBytes.isPresent() && sizeBytes.orElseThrow() < 0) {
            throw new IllegalArgumentException("The Docker volume size is not valid.");
        }
        if (!"EXACT".equals(measurement) && !"UNAVAILABLE".equals(measurement)) {
            throw new IllegalArgumentException("The Docker volume measurement is not valid.");
        }
        if ((sizeBytes.isPresent()) != "EXACT".equals(measurement)) {
            throw new IllegalArgumentException("The Docker volume measurement is inconsistent.");
        }
    }
}
