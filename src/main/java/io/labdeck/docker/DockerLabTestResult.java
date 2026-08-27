package io.labdeck.docker;

import java.util.Objects;
import java.util.Optional;

public record DockerLabTestResult(
        DockerTestExecutionResult execution,
        Optional<DockerTestCancelCause> cancelCause) {

    public DockerLabTestResult {
        Objects.requireNonNull(execution, "execution");
        cancelCause = cancelCause == null ? Optional.empty() : cancelCause;
        if (execution.state() == DockerTestExecutionState.CANCELLED && cancelCause.isEmpty()) {
            throw new IllegalArgumentException("A cancelled lab test needs a cancellation cause.");
        }
        if (execution.state() != DockerTestExecutionState.CANCELLED && cancelCause.isPresent()) {
            throw new IllegalArgumentException("Only a cancelled lab test can have a cancellation cause.");
        }
    }
}
