package io.labdeck.docker;

import java.util.Objects;
import java.util.OptionalInt;

public record DockerTestExecutionResult(
        DockerTestExecutionState state,
        OptionalInt exitCode,
        String stdout,
        String stderr,
        boolean stdoutTruncated,
        boolean stderrTruncated) {

    public DockerTestExecutionResult {
        Objects.requireNonNull(state, "state");
        exitCode = exitCode == null ? OptionalInt.empty() : exitCode;
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        if (state == DockerTestExecutionState.COMPLETED && exitCode.isEmpty()) {
            throw new IllegalArgumentException("A completed Docker test needs an exit code.");
        }
        if (state != DockerTestExecutionState.COMPLETED && exitCode.isPresent()) {
            throw new IllegalArgumentException("An incomplete Docker test cannot invent an exit code.");
        }
    }

    @Override
    public String toString() {
        return "DockerTestExecutionResult[state=" + state
                + ", exitCode=" + exitCode
                + ", stdoutBytes=" + stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + ", stderrBytes=" + stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + ", stdoutTruncated=" + stdoutTruncated
                + ", stderrTruncated=" + stderrTruncated + "]";
    }
}
