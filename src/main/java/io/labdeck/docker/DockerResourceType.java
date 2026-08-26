package io.labdeck.docker;

public enum DockerResourceType {
    CONTAINER,
    NETWORK,
    VOLUME;

    public String labelValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
