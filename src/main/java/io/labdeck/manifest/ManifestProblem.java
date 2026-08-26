package io.labdeck.manifest;

import java.util.Objects;

public record ManifestProblem(ManifestProblemCode code, String path, String message) implements Comparable<ManifestProblem> {

    public ManifestProblem {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
    }

    @Override
    public int compareTo(ManifestProblem other) {
        int pathComparison = path.compareTo(other.path);
        return pathComparison != 0 ? pathComparison : code.compareTo(other.code);
    }
}
