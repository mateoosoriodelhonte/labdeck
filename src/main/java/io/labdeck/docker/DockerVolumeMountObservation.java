package io.labdeck.docker;

public record DockerVolumeMountObservation(String volume, String target, boolean readOnly) {

    public DockerVolumeMountObservation {
        DockerResourceRecord.requireLogicalName(volume);
        if (target == null
                || target.isBlank()
                || target.length() > 256
                || !target.startsWith("/")
                || target.contains("//")
                || target.contains("/../")
                || target.endsWith("/..")
                || target.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("The observed Docker volume target is not valid.");
        }
    }
}
