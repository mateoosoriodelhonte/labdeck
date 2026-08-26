package io.labdeck.manifest;

import java.util.List;

public final class ManifestValidationException extends IllegalArgumentException {

    private final List<ManifestProblem> problems;

    public ManifestValidationException(List<ManifestProblem> problems) {
        super("The lab manifest is not valid.");
        if (problems == null || problems.isEmpty()) {
            throw new IllegalArgumentException("At least one manifest problem is required.");
        }
        this.problems = problems.stream().sorted().toList();
    }

    public List<ManifestProblem> problems() {
        return problems;
    }
}
