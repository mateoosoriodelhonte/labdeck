package io.labdeck.docker;

import java.util.List;
import java.util.function.Consumer;

interface DockerRuntimeMonitorPort extends AutoCloseable {

    void watch(
            String labId,
            long runningRevision,
            List<DockerReadinessWaiter.ServiceProbe> probes,
            Consumer<RuntimeFailure> failureHandler);

    void cancel(String labId);

    @Override
    void close();

    record RuntimeFailure(
            boolean engineInspectionFailed,
            boolean ownershipMismatch,
            DockerServiceReadinessException serviceFailure) {
        public RuntimeFailure {
            int kinds = (engineInspectionFailed ? 1 : 0)
                    + (ownershipMismatch ? 1 : 0)
                    + (serviceFailure != null ? 1 : 0);
            if (kinds != 1) {
                throw new IllegalArgumentException("The runtime failure kind is not valid.");
            }
        }

        static RuntimeFailure inspectionFailed() {
            return new RuntimeFailure(true, false, null);
        }

        static RuntimeFailure ownershipChanged() {
            return new RuntimeFailure(false, true, null);
        }

        static RuntimeFailure serviceFailed(DockerServiceReadinessException failure) {
            return new RuntimeFailure(
                    false, false, java.util.Objects.requireNonNull(failure, "failure"));
        }
    }
}
