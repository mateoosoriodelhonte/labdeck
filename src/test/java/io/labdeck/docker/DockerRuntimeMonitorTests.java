package io.labdeck.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DockerRuntimeMonitorTests {

    @Test
    void reportsAnUnexpectedExitWithinOneMonitorTick() throws Exception {
        AtomicReference<DockerContainerView> view = new AtomicReference<>(view(true));
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<DockerRuntimeMonitorPort.RuntimeFailure> failure = new AtomicReference<>();
        DockerRuntimeMonitor monitor = monitor();
        try {
            monitor.watch(
                    "lab-a",
                    1,
                    List.of(new DockerReadinessWaiter.ServiceProbe("app", true, view::get)),
                    value -> {
                        failure.set(value);
                        failed.countDown();
                    });
            view.set(view(false));

            assertThat(failed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get().engineInspectionFailed()).isFalse();
            assertThat(failure.get().ownershipMismatch()).isFalse();
            assertThat(failure.get().serviceFailure().reason())
                    .isEqualTo(DockerServiceReadinessException.Reason.EXITED);
        } finally {
            monitor.close();
        }
    }

    @Test
    void threeInspectionFailuresReportDegradedEngineStateWithoutAServiceGuess() throws Exception {
        AtomicInteger inspections = new AtomicInteger();
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<DockerRuntimeMonitorPort.RuntimeFailure> failure = new AtomicReference<>();
        DockerRuntimeMonitor monitor = monitor();
        try {
            monitor.watch(
                    "lab-a",
                    1,
                    List.of(new DockerReadinessWaiter.ServiceProbe("app", true, () -> {
                        inspections.incrementAndGet();
                        throw new IllegalStateException("simulated daemon disconnect with raw details");
                    })),
                    value -> {
                        failure.set(value);
                        failed.countDown();
                    });

            assertThat(failed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(inspections).hasValue(3);
            assertThat(failure.get().engineInspectionFailed()).isTrue();
            assertThat(failure.get().ownershipMismatch()).isFalse();
            assertThat(failure.get().serviceFailure()).isNull();
        } finally {
            monitor.close();
        }
    }

    @Test
    void anOwnershipMismatchIsReportedImmediatelyWithoutDaemonRetries() throws Exception {
        AtomicInteger inspections = new AtomicInteger();
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<DockerRuntimeMonitorPort.RuntimeFailure> failure = new AtomicReference<>();
        DockerRuntimeMonitor monitor = monitor();
        try {
            monitor.watch(
                    "lab-a",
                    1,
                    List.of(new DockerReadinessWaiter.ServiceProbe("app", true, () -> {
                        inspections.incrementAndGet();
                        throw new DockerOwnershipException("simulated ownership mismatch");
                    })),
                    value -> {
                        failure.set(value);
                        failed.countDown();
                    });

            assertThat(failed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(inspections).hasValue(1);
            assertThat(failure.get().ownershipMismatch()).isTrue();
            assertThat(failure.get().engineInspectionFailed()).isFalse();
            assertThat(failure.get().serviceFailure()).isNull();
        } finally {
            monitor.close();
        }
    }

    private static DockerRuntimeMonitor monitor() {
        return new DockerRuntimeMonitor(
                Duration.ofMillis(1),
                duration -> Thread.sleep(duration.toMillis()),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    private static DockerContainerView view(boolean running) {
        return new DockerContainerView(
                "container-id",
                "app",
                "container-name",
                "image-id",
                running ? "running" : "exited",
                running,
                running ? OptionalInt.empty() : OptionalInt.of(0),
                DockerHealthStatus.HEALTHY,
                List.of());
    }
}
