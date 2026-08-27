package io.labdeck.docker;

import java.util.List;

public record DockerLogBatch(List<DockerLogLine> lines, boolean truncated) {

    public DockerLogBatch {
        lines = List.copyOf(lines);
    }
}
