package io.labdeck.docker;

import java.time.Instant;
import java.util.Objects;

public record DockerLogLine(Instant timestamp, String service, String stream, String text) {

    public DockerLogLine {
        Objects.requireNonNull(timestamp, "timestamp");
        DockerResourceRecord.requireLogicalName(service);
        if (!"STDOUT".equals(stream) && !"STDERR".equals(stream) && !"CONSOLE".equals(stream)) {
            throw new IllegalArgumentException("The Docker log stream is not valid.");
        }
        Objects.requireNonNull(text, "text");
    }
}
