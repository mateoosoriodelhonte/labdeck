package io.labdeck.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.labdeck.lab.LabFailureCode;
import io.labdeck.lab.LabRecord;
import io.labdeck.lab.LabRepository;
import io.labdeck.lab.LabRuntimeFailure;
import io.labdeck.lab.LabState;
import io.labdeck.manifest.ApprovedWorkspacePath;
import io.labdeck.manifest.ManifestPlan;
import io.labdeck.manifest.ManifestPlanCompiler;
import io.labdeck.manifest.ProjectPathPolicy;
import io.labdeck.manifest.RestrictedManifestParser;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerLabLifecycleTests {

    private static final Instant NOW = Instant.parse("2026-08-26T20:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void startsByImmutableImageIdAndStopsOnlyJournaledResources() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Files.writeString(workspace.resolve("student.txt"), "keep me");
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        engine.resources.put("foreign-sentinel", null);
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);

        DockerStartResult started = lifecycle.start(lab, plan(), CancellationToken.NONE);

        assertThat(started.lab().state()).isEqualTo(LabState.RUNNING);
        assertThat(started.containers()).hasSize(2).allSatisfy(container ->
                assertThat(container.image()).isEqualTo("sha256:immutable-busybox"));
        assertThat(engine.createdSpecifications.values())
                .extracting(DockerContainerSpec::image)
                .containsOnly("sha256:immutable-busybox");
        assertThat(engine.createdSpecifications.values())
                .extracting(DockerContainerSpec::resourceLimits)
                .containsOnly(new DockerContainerSpec.ResourceLimits(
                        500_000_000L, 1_000_000_000L));
        assertThat(engine.createdSpecifications.values().stream()
                        .map(DockerContainerSpec::resourceLimits)
                        .mapToLong(DockerContainerSpec.ResourceLimits::memoryBytes)
                        .sum())
                .isEqualTo(1_000_000_000L);
        assertThat(engine.createdSpecifications.values().stream()
                        .map(DockerContainerSpec::resourceLimits)
                        .mapToLong(DockerContainerSpec.ResourceLimits::nanoCpus)
                        .sum())
                .isEqualTo(2_000_000_000L);
        assertThat(engine.resources).containsKey("foreign-sentinel");

        LabRecord stopped = lifecycle.stop(lab.id());

        assertThat(stopped.state()).isEqualTo(LabState.STOPPED);
        assertThat(engine.resources).containsKey("foreign-sentinel");
        assertThat(engine.resources.keySet()).anyMatch(id -> id.startsWith("volume-"));
        assertThat(engine.resources.keySet()).noneMatch(id -> id.startsWith("container-"));
        assertThat(engine.resources.keySet()).noneMatch(id -> id.startsWith("network-"));
        assertThat(Files.readString(workspace.resolve("student.txt"))).isEqualTo("keep me");
        assertThat(engine.calls).endsWith(
                "stop:container-database",
                "remove:container-database",
                "stop:container-app",
                "remove:container-app",
                "remove:network-lab-network",
                "verify:volume-course-data");
    }

    @Test
    void inspectsOnlyActiveContainersJournaledToTheSelectedRunningLab() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("inspect-workspace"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);
        lifecycle.start(lab, plan(), CancellationToken.NONE);

        DockerServiceSnapshot snapshot = lifecycle.inspectServiceSnapshot(lab.id());
        List<DockerContainerView> services = snapshot.services();

        assertThat(snapshot.lab().revision()).isEqualTo(2);
        assertThat(services).extracting(DockerContainerView::service)
                .containsExactly("app", "database");
        assertThat(services).allSatisfy(service -> {
            assertThat(service.running()).isTrue();
            assertThat(service.id()).startsWith("container-");
        });
        int callsBeforeMissingLab = engine.calls.size();
        assertThatThrownBy(() -> lifecycle.inspectServiceSnapshot("missing-lab"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist");
        assertThat(engine.calls).hasSize(callsBeforeMissingLab);
    }

    @Test
    void stopRejectsAStaleExpectedRevisionBeforeChangingResources() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("revision-workspace"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);
        DockerStartResult started = lifecycle.start(lab, plan(), CancellationToken.NONE);
        int callsBeforeStop = engine.calls.size();

        assertThatThrownBy(() -> lifecycle.stop(lab.id(), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed");
        assertThat(engine.calls).hasSize(callsBeforeStop);
        assertThat(labs.findById(lab.id()).orElseThrow().state()).isEqualTo(LabState.RUNNING);

        LabRecord stopped = lifecycle.stop(lab.id(), started.lab().revision());
        assertThat(stopped.state()).isEqualTo(LabState.STOPPED);
    }

    @Test
    void missingImagesRequireConfirmationBeforeAnyLifecycleMutation() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);

        assertThatThrownBy(() -> lifecycle.start(lab, plan(), CancellationToken.NONE))
                .isInstanceOfSatisfying(DockerImagesRequiredException.class, exception ->
                        assertThat(exception.missingImages()).containsExactly("busybox:1.37"));

        assertThat(labs.findById(lab.id()).orElseThrow().state()).isEqualTo(LabState.IMPORTED);
        assertThat(journal.findOpenByLab(new LabOwnership(lab.id(), lab.projectId()))).isEmpty();
        assertThat(engine.resources).isEmpty();
    }

    @Test
    void passesCancellationIntoAConfirmedPublicImagePull() {
        MemoryLabRepository labs = new MemoryLabRepository(lab(temporaryDirectory));
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);
        CancellationToken cancellation = () -> false;

        lifecycle.pullConfirmedImages(plan(), List.of("busybox:1.37"), cancellation);

        assertThat(engine.pullCancellation).isSameAs(cancellation);
        assertThat(engine.inspectImage("busybox:1.37")).isPresent();
    }

    @Test
    void aPreDispatchReservationNeverAdoptsAnExactLabelSentinel() {
        LabRecord failedLab = lab(temporaryDirectory, LabState.FAILED);
        MemoryLabRepository labs = new MemoryLabRepository(failedLab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        DockerResourceRecord reserved = DockerResourceRecord.reserved(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                new LabOwnership(failedLab.id(), failedLab.projectId()),
                DockerResourceType.CONTAINER,
                "app",
                NOW);
        journal.reserve(reserved);
        engine.resources.put("foreign-exact-label-sentinel", reserved);
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);

        LabRecord stopped = lifecycle.stop(failedLab.id());

        assertThat(stopped.state()).isEqualTo(LabState.STOPPED);
        assertThat(engine.resources).containsKey("foreign-exact-label-sentinel");
        assertThat(journal.findOpenByLab(reserved.ownership())).isEmpty();
    }

    @Test
    void aDelayedCreateRemainsJournaledUntilItCanBeReconciled() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("delayed-workspace"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        engine.failNetworkAfterDispatch = true;
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);

        assertThatThrownBy(() -> lifecycle.start(lab, plan(), CancellationToken.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambiguous");
        DockerResourceRecord dispatched = journal.findOpenByLab(
                        new LabOwnership(lab.id(), lab.projectId()))
                .getFirst();
        assertThat(dispatched.state()).isEqualTo(DockerResourceState.DISPATCHED);
        assertThat(labs.findById(lab.id()).orElseThrow().state()).isEqualTo(LabState.FAILED);

        engine.materializeDelayedNetwork();
        LabRecord stopped = lifecycle.stop(lab.id());

        assertThat(stopped.state()).isEqualTo(LabState.STOPPED);
        assertThat(engine.resources.keySet()).noneMatch(id -> id.startsWith("network-"));
        assertThat(journal.findOpenByLab(dispatched.ownership())).isEmpty();
    }

    @Test
    void cancelledStartCleansEphemeralResourcesAndEndsStopped() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("cancel-workspace"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        AtomicBoolean cancelled = new AtomicBoolean();
        engine.afterStart = () -> cancelled.set(true);
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);

        assertThatThrownBy(() -> lifecycle.start(lab, plan(), cancelled::get))
                .isInstanceOf(DockerOperationCancelledException.class);

        assertThat(labs.findById(lab.id()).orElseThrow().state()).isEqualTo(LabState.STOPPED);
        assertThat(engine.resources.keySet()).anyMatch(id -> id.startsWith("volume-"));
        assertThat(engine.resources.keySet()).noneMatch(id -> id.startsWith("container-"));
        assertThat(engine.resources.keySet()).noneMatch(id -> id.startsWith("network-"));
    }

    @Test
    void stopSignalsAHealthWaitBeforeItTakesTheLifecycleLock() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("stop-during-start"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        engine.healthStatus = DockerHealthStatus.STARTING;
        CountDownLatch startedContainer = new CountDownLatch(1);
        engine.afterStart = startedContainer::countDown;
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> start = executor.submit(() -> lifecycle.start(lab, plan(), CancellationToken.NONE));
            assertThat(startedContainer.await(2, TimeUnit.SECONDS)).isTrue();

            LabRecord stopped = lifecycle.stop(lab.id());

            assertThat(stopped.state()).isEqualTo(LabState.STOPPED);
            assertThatThrownBy(() -> start.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DockerOperationCancelledException.class);
            assertThat(engine.resources.keySet()).noneMatch(id -> id.startsWith("container-"));
            assertThat(engine.resources.keySet()).noneMatch(id -> id.startsWith("network-"));
        }
    }

    @Test
    void stopRequestCancelsAStartThatRegistersAfterTheActiveStartLookup() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("stop-registration-race"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        FakeRuntimeMonitor monitor = new FakeRuntimeMonitor();
        monitor.blockCancellation();
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs, monitor);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<LabRecord> stop = executor.submit(() -> lifecycle.stop(lab.id()));
            try {
                assertThat(monitor.awaitCancellation()).isTrue();

                Future<?> start = executor.submit(() -> lifecycle.start(lab, plan(), CancellationToken.NONE));

                assertThatThrownBy(() -> start.get(2, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(DockerOperationCancelledException.class);
                assertThat(engine.calls).isEmpty();
            } finally {
                monitor.releaseCancellation();
            }

            assertThat(stop.get(2, TimeUnit.SECONDS).state()).isEqualTo(LabState.STOPPED);
            assertThat(labs.findById(lab.id()).orElseThrow().state()).isEqualTo(LabState.STOPPED);
            assertThat(engine.resources).isEmpty();
        }
    }

    @Test
    void cancellationThatWinsTheRunningCommitGateCannotReportSuccess() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("cancel-at-commit"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        BlockingClock clock = new BlockingClock();
        AtomicInteger inspections = new AtomicInteger();
        engine.afterInspect = () -> {
            if (inspections.incrementAndGet() == 4) {
                clock.blockNextInstant();
            }
        };
        AtomicBoolean cancelled = new AtomicBoolean();
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs, clock);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> start = executor.submit(() -> lifecycle.start(lab, plan(), cancelled::get));
            assertThat(clock.awaitBlocked()).isTrue();

            cancelled.set(true);
            clock.release();

            assertThatThrownBy(() -> start.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DockerOperationCancelledException.class);
            assertThat(labs.findById(lab.id()).orElseThrow().state()).isEqualTo(LabState.STOPPED);
            assertThat(engine.resources.keySet())
                    .noneMatch(id -> id.startsWith("container-") || id.startsWith("network-"));
        }
    }

    @Test
    void failedCleanupRetryStoresANewDurableCleanupFailure() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("failed-cleanup-retry"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        engine.healthStatus = DockerHealthStatus.UNHEALTHY;
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);
        assertThatThrownBy(() -> lifecycle.start(lab, plan(), CancellationToken.NONE))
                .isInstanceOf(DockerServiceReadinessException.class);
        long failedRevision = labs.findById(lab.id()).orElseThrow().revision();
        engine.failVolumeVerification = true;

        assertThatThrownBy(() -> lifecycle.stop(lab.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("volume verification");

        LabRecord failed = labs.findById(lab.id()).orElseThrow();
        assertThat(failed.state()).isEqualTo(LabState.FAILED);
        assertThat(failed.revision()).isEqualTo(failedRevision + 2);
        assertThat(labs.findRuntimeFailure(lab.id()).orElseThrow())
                .satisfies(failure -> {
                    assertThat(failure.code()).isEqualTo(LabFailureCode.CLEANUP_INCOMPLETE);
                    assertThat(failure.cleanupIncomplete()).isTrue();
                    assertThat(failure.labRevision()).isEqualTo(failed.revision());
                });
    }

    @Test
    void successfulCleanupRetryClearsTheOldFailure() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("successful-cleanup-retry"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        engine.healthStatus = DockerHealthStatus.UNHEALTHY;
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);
        assertThatThrownBy(() -> lifecycle.start(lab, plan(), CancellationToken.NONE))
                .isInstanceOf(DockerServiceReadinessException.class);

        assertThat(lifecycle.stop(lab.id()).state()).isEqualTo(LabState.STOPPED);
        assertThat(labs.findRuntimeFailure(lab.id())).isEmpty();
    }

    @Test
    void DockerStorageExhaustionGetsASafeDurableFailure() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("storage-full"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        engine.startFailure = new DockerStorageFullException();
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);

        assertThatThrownBy(() -> lifecycle.start(lab, plan(), CancellationToken.NONE))
                .isInstanceOf(DockerStorageFullException.class)
                .hasMessageNotContaining("/var/lib/docker");

        LabRuntimeFailure failure = labs.findRuntimeFailure(lab.id()).orElseThrow();
        assertThat(failure.code()).isEqualTo(LabFailureCode.DOCKER_STORAGE_FULL);
        assertThat(failure.safeMessage()).contains("did not delete or prune anything");
    }

    @Test
    void anUnhealthyOrExitedServiceNeverCommitsRunning() throws Exception {
        Path unhealthyWorkspace = Files.createDirectories(temporaryDirectory.resolve("unhealthy"));
        LabRecord unhealthyLab = lab(unhealthyWorkspace);
        MemoryLabRepository unhealthyLabs = new MemoryLabRepository(unhealthyLab);
        MemoryJournal unhealthyJournal = new MemoryJournal();
        FakeEngine unhealthyEngine = new FakeEngine();
        unhealthyEngine.addImage("busybox:1.37", "sha256:immutable-busybox");
        unhealthyEngine.healthStatus = DockerHealthStatus.UNHEALTHY;
        DockerLabLifecycle unhealthyLifecycle = lifecycle(
                unhealthyEngine, unhealthyJournal, unhealthyLabs);

        assertThatThrownBy(() -> unhealthyLifecycle.start(
                unhealthyLab, plan(), CancellationToken.NONE))
                .isInstanceOfSatisfying(DockerServiceReadinessException.class, exception ->
                        assertThat(exception.reason())
                                .isEqualTo(DockerServiceReadinessException.Reason.UNHEALTHY));
        assertThat(unhealthyLabs.findById(unhealthyLab.id()).orElseThrow().state())
                .isEqualTo(LabState.FAILED);
        assertThat(unhealthyLabs.findRuntimeFailure(unhealthyLab.id()).orElseThrow().code())
                .isEqualTo(LabFailureCode.HEALTHCHECK_UNHEALTHY);
        assertThat(unhealthyEngine.resources.keySet())
                .noneMatch(id -> id.startsWith("container-") || id.startsWith("network-"));

        Path exitedWorkspace = Files.createDirectories(temporaryDirectory.resolve("exited"));
        LabRecord exitedLab = lab(exitedWorkspace);
        MemoryLabRepository exitedLabs = new MemoryLabRepository(exitedLab);
        MemoryJournal exitedJournal = new MemoryJournal();
        FakeEngine exitedEngine = new FakeEngine();
        exitedEngine.addImage("busybox:1.37", "sha256:immutable-busybox");
        exitedEngine.afterStart = () -> exitedEngine.containersRunning = false;
        DockerLabLifecycle exitedLifecycle = lifecycle(exitedEngine, exitedJournal, exitedLabs);

        assertThatThrownBy(() -> exitedLifecycle.start(exitedLab, plan(), CancellationToken.NONE))
                .isInstanceOfSatisfying(DockerServiceReadinessException.class, exception -> {
                    assertThat(exception.reason())
                            .isEqualTo(DockerServiceReadinessException.Reason.EXITED);
                    assertThat(exception.exitCode()).hasValue(0);
                });
        assertThat(exitedLabs.findById(exitedLab.id()).orElseThrow().state())
                .isEqualTo(LabState.FAILED);
        assertThat(exitedLabs.findRuntimeFailure(exitedLab.id()).orElseThrow().code())
                .isEqualTo(LabFailureCode.CONTAINER_EXITED);
    }

    @Test
    void aRuntimeExitFailsOnlyTheExactRunningRevision() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("runtime-exit"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        FakeRuntimeMonitor monitor = new FakeRuntimeMonitor();
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs, monitor);

        DockerStartResult first = lifecycle.start(lab, plan(), CancellationToken.NONE);
        Consumer<DockerRuntimeMonitorPort.RuntimeFailure> oldHandler = monitor.failureHandler;
        LabRecord stopped = lifecycle.stop(lab.id());
        DockerStartResult second = lifecycle.start(stopped, plan(), CancellationToken.NONE);

        oldHandler.accept(DockerRuntimeMonitorPort.RuntimeFailure.serviceFailed(
                DockerServiceReadinessException.exited("app", java.util.OptionalInt.of(0))));
        assertThat(labs.findById(lab.id()).orElseThrow()).isEqualTo(second.lab());
        assertThat(labs.findById(lab.id()).orElseThrow().state()).isEqualTo(LabState.RUNNING);

        monitor.failureHandler.accept(DockerRuntimeMonitorPort.RuntimeFailure.serviceFailed(
                DockerServiceReadinessException.exited("app", java.util.OptionalInt.of(0))));
        assertThat(labs.findById(lab.id()).orElseThrow().state()).isEqualTo(LabState.FAILED);
        LabRuntimeFailure runtimeFailure = labs.findRuntimeFailure(lab.id()).orElseThrow();
        assertThat(runtimeFailure.code()).isEqualTo(LabFailureCode.CONTAINER_EXITED);
        assertThat(runtimeFailure.service()).contains("app");
        assertThat(runtimeFailure.safeMessage()).contains("exited unexpectedly");
        assertThat(engine.resources.keySet()).anyMatch(id -> id.startsWith("volume-"));
        assertThat(engine.resources.keySet())
                .noneMatch(id -> id.startsWith("container-") || id.startsWith("network-"));
        assertThat(first.lab().revision()).isLessThan(second.lab().revision());
    }

    @Test
    void aMonitorRegistrationFailureCleansACommittedRun() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("monitor-registration"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        FakeRuntimeMonitor monitor = new FakeRuntimeMonitor();
        monitor.failRegistration = true;
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs, monitor);

        assertThatThrownBy(() -> lifecycle.start(lab, plan(), CancellationToken.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("monitor registration");

        assertThat(labs.findById(lab.id()).orElseThrow().state()).isEqualTo(LabState.FAILED);
        assertThat(labs.findRuntimeFailure(lab.id()).orElseThrow().code())
                .isEqualTo(LabFailureCode.CONTAINER_START_FAILED);
        assertThat(engine.resources.keySet()).anyMatch(id -> id.startsWith("volume-"));
        assertThat(engine.resources.keySet())
                .noneMatch(id -> id.startsWith("container-") || id.startsWith("network-"));
    }

    @Test
    void aRuntimeOwnershipMismatchFailsWithoutUnverifiedCleanup() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("runtime-ownership"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        FakeRuntimeMonitor monitor = new FakeRuntimeMonitor();
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs, monitor);
        lifecycle.start(lab, plan(), CancellationToken.NONE);

        monitor.failureHandler.accept(
                DockerRuntimeMonitorPort.RuntimeFailure.ownershipChanged());

        assertThat(labs.findById(lab.id()).orElseThrow().state()).isEqualTo(LabState.FAILED);
        LabRuntimeFailure failure = labs.findRuntimeFailure(lab.id()).orElseThrow();
        assertThat(failure.code()).isEqualTo(LabFailureCode.OWNERSHIP_MISMATCH);
        assertThat(failure.cleanupIncomplete()).isTrue();
        assertThat(engine.resources.keySet())
                .anyMatch(id -> id.startsWith("container-"))
                .anyMatch(id -> id.startsWith("network-"));
    }

    @Test
    void unsafeDirectPlanLimitsFailBeforeAnyDockerCall() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("unsafe-direct-plan"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        ManifestPlan safe = plan();
        ManifestPlan unsafe = new ManifestPlan(
                safe.schemaVersion(),
                safe.manifestSha256(),
                safe.name(),
                safe.workspaceMount(),
                new io.labdeck.manifest.LabManifest.ResourceLimits(0, new BigDecimal("2")),
                safe.services(),
                safe.images(),
                safe.builds(),
                safe.volumes(),
                safe.tests());
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);

        assertThatThrownBy(() -> lifecycle.start(lab, unsafe, CancellationToken.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory");

        assertThat(engine.calls).isEmpty();
        assertThat(labs.findById(lab.id()).orElseThrow().state()).isEqualTo(LabState.IMPORTED);
    }

    @Test
    void rejectsAWorkspaceIdentitySwapAfterManifestReview() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("reviewed-workspace"));
        ApprovedWorkspacePath approved = new ProjectPathPolicy().resolveWorkspace(workspace);
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);
        Files.move(workspace, temporaryDirectory.resolve("original-workspace"));
        Files.createDirectory(workspace);

        assertThatThrownBy(() -> lifecycle.start(
                        lab, approved, plan(), CancellationToken.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved workspace changed");

        assertThat(engine.calls).isEmpty();
        assertThat(labs.findById(lab.id()).orElseThrow().state()).isEqualTo(LabState.IMPORTED);
        assertThat(journal.findOpenByLab(new LabOwnership(lab.id(), lab.projectId()))).isEmpty();
    }

    private DockerLabLifecycle lifecycle(
            FakeEngine engine, MemoryJournal journal, MemoryLabRepository labs) {
        AtomicInteger tokens = new AtomicInteger();
        return new DockerLabLifecycle(
                engine,
                journal,
                labs,
                new ProjectPathPolicy(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "%032x".formatted(tokens.incrementAndGet()));
    }

    private DockerLabLifecycle lifecycle(
            FakeEngine engine, MemoryJournal journal, MemoryLabRepository labs, Clock clock) {
        AtomicInteger tokens = new AtomicInteger();
        return new DockerLabLifecycle(
                engine,
                journal,
                labs,
                new ProjectPathPolicy(),
                clock,
                () -> "%032x".formatted(tokens.incrementAndGet()));
    }

    private DockerLabLifecycle lifecycle(
            FakeEngine engine,
            MemoryJournal journal,
            MemoryLabRepository labs,
            DockerRuntimeMonitorPort monitor) {
        AtomicInteger tokens = new AtomicInteger();
        return new DockerLabLifecycle(
                engine,
                journal,
                labs,
                new ProjectPathPolicy(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "%032x".formatted(tokens.incrementAndGet()),
                new DockerReadinessWaiter(),
                monitor);
    }

    private static LabRecord lab(Path workspace) {
        return lab(workspace, LabState.IMPORTED);
    }

    private static LabRecord lab(Path workspace, LabState state) {
        return new LabRecord(
                "lab-a", "project-a", "Lifecycle lab", 1, workspace,
                state, 0, NOW, NOW);
    }

    private static ManifestPlan plan() {
        String yaml = """
                version: 1
                name: Lifecycle lab
                workspace:
                  mount: /workspace
                services:
                  app:
                    image: busybox:1.37
                    command: ["sleep", "30"]
                    healthcheck:
                      command: ["true"]
                    volumes:
                      - name: course-data
                        target: /data
                  database:
                    image: busybox:1.37
                    command: ["sleep", "30"]
                    healthcheck:
                      command: ["true"]
                """;
        return new ManifestPlanCompiler().compile(new RestrictedManifestParser().parse(yaml));
    }

    private static final class MemoryLabRepository implements LabRepository {
        private final Map<String, LabRecord> records = new LinkedHashMap<>();
        private final Map<String, LabRuntimeFailure> failures = new LinkedHashMap<>();

        private MemoryLabRepository(LabRecord lab) {
            records.put(lab.id(), lab);
        }

        @Override
        public void create(LabRecord lab) {
            records.put(lab.id(), lab);
        }

        @Override
        public Optional<LabRecord> findById(String id) {
            return Optional.ofNullable(records.get(id));
        }

        @Override
        public List<LabRecord> findAll() {
            return List.copyOf(records.values());
        }

        @Override
        public boolean compareAndSetState(
                String id, long revision, LabState expected, LabState next, Instant updatedAt) {
            LabRecord current = records.get(id);
            if (current == null || current.revision() != revision || current.state() != expected) {
                return false;
            }
            records.put(id, current.transitionTo(next, updatedAt));
            if (next == LabState.STARTING || next == LabState.STOPPED) {
                failures.remove(id);
            }
            return true;
        }

        @Override
        public boolean compareAndSetStateWithFailure(
                String id,
                long expectedRevision,
                LabState expected,
                Instant updatedAt,
                LabRuntimeFailure failure) {
            if (!compareAndSetState(
                    id, expectedRevision, expected, LabState.FAILED, updatedAt)) {
                return false;
            }
            failures.put(id, failure);
            return true;
        }

        @Override
        public Optional<LabRuntimeFailure> findRuntimeFailure(String labId) {
            return Optional.ofNullable(failures.get(labId));
        }
    }

    private static final class MemoryJournal implements DockerResourceJournal {
        private final Map<String, DockerResourceRecord> records = new LinkedHashMap<>();

        @Override
        public void reserve(DockerResourceRecord resource) {
            if (findOpen(resource.ownership(), resource.type(), resource.logicalName()).isPresent()) {
                throw new IllegalStateException("duplicate open resource");
            }
            records.put(resource.ownershipToken(), resource);
        }

        @Override
        public boolean markDispatched(String token, Instant updatedAt) {
            DockerResourceRecord current = records.get(token);
            if (current == null || current.state() != DockerResourceState.RESERVED) {
                return false;
            }
            records.put(token, current.dispatch(updatedAt));
            return true;
        }

        @Override
        public boolean activate(
                String token, String engineId, Optional<String> engineIdentity, Instant updatedAt) {
            DockerResourceRecord current = records.get(token);
            if (current == null || current.state() != DockerResourceState.DISPATCHED) {
                return false;
            }
            records.put(token, current.activate(
                    new DockerCreatedResource(engineId, engineIdentity), updatedAt));
            return true;
        }

        @Override
        public boolean discardReservation(String token, Instant updatedAt) {
            return discardPending(token, DockerResourceState.RESERVED, updatedAt);
        }

        @Override
        public boolean closeDispatchWithoutResource(String token, Instant updatedAt) {
            return discardPending(token, DockerResourceState.DISPATCHED, updatedAt);
        }

        @Override
        public boolean markRemoved(String token, String expectedEngineId, Instant updatedAt) {
            DockerResourceRecord current = records.get(token);
            if (current == null || current.state() == DockerResourceState.REMOVED
                    || !current.engineId().equals(Optional.ofNullable(expectedEngineId))) {
                return false;
            }
            records.put(token, new DockerResourceRecord(
                    current.ownershipToken(), current.ownership(), current.type(), current.logicalName(),
                    current.engineId(), current.engineIdentity(), DockerResourceState.REMOVED,
                    current.createdAt(), updatedAt));
            return true;
        }

        private boolean discardPending(
                String token, DockerResourceState expected, Instant updatedAt) {
            DockerResourceRecord current = records.get(token);
            if (current == null || current.state() != expected || current.engineId().isPresent()) {
                return false;
            }
            records.put(token, new DockerResourceRecord(
                    current.ownershipToken(), current.ownership(), current.type(), current.logicalName(),
                    Optional.empty(), Optional.empty(), DockerResourceState.REMOVED,
                    current.createdAt(), updatedAt));
            return true;
        }

        @Override
        public Optional<DockerResourceRecord> findOpen(
                LabOwnership ownership, DockerResourceType type, String logicalName) {
            return records.values().stream()
                    .filter(record -> record.ownership().equals(ownership))
                    .filter(record -> record.type() == type && record.logicalName().equals(logicalName))
                    .filter(record -> record.state() != DockerResourceState.REMOVED)
                    .findFirst();
        }

        @Override
        public List<DockerResourceRecord> findOpenByLab(LabOwnership ownership) {
            return records.values().stream()
                    .filter(record -> record.ownership().equals(ownership))
                    .filter(record -> record.state() != DockerResourceState.REMOVED)
                    .toList();
        }
    }

    private static final class FakeEngine implements DockerEnginePort {
        private final Map<String, DockerImageMetadata> images = new LinkedHashMap<>();
        private final Map<String, DockerResourceRecord> resources = new LinkedHashMap<>();
        private final Map<String, DockerContainerSpec> createdSpecifications = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();
        private CancellationToken pullCancellation;
        private boolean failNetworkAfterDispatch;
        private DockerResourceRecord delayedNetwork;
        private Runnable afterStart = () -> {};
        private Runnable afterInspect = () -> {};
        private RuntimeException startFailure;
        private DockerHealthStatus healthStatus = DockerHealthStatus.HEALTHY;
        private boolean containersRunning = true;
        private boolean failVolumeVerification;

        void addImage(String reference, String id) {
            DockerImageMetadata metadata = new DockerImageMetadata(id, 123, Set.of(), false);
            images.put(reference, metadata);
            images.put(id, metadata);
        }

        @Override
        public void verifyAvailable() {
            calls.add("ping");
        }

        @Override
        public void verifyLocalPortPublishingSupported() {
            calls.add("verify-ports");
        }

        @Override
        public void verifyResourceLimitsSupported() {
            calls.add("verify-limits");
        }

        @Override
        public Optional<DockerImageMetadata> inspectImage(String reference) {
            return Optional.ofNullable(images.get(reference));
        }

        @Override
        public void pullPublicImageAfterConfirmation(
                String reference, Duration timeout, CancellationToken cancellation) {
            pullCancellation = cancellation;
            addImage(reference, "sha256:confirmed-pull");
        }

        @Override
        public Optional<DockerCreatedResource> reconcileDispatched(DockerResourceRecord dispatched) {
            return resources.entrySet().stream()
                    .filter(entry -> dispatched.equals(entry.getValue()))
                    .map(entry -> created(entry.getKey(), entry.getValue()))
                    .findFirst();
        }

        @Override
        public DockerCreatedResource createNetwork(DockerResourceRecord dispatched) {
            if (failNetworkAfterDispatch) {
                failNetworkAfterDispatch = false;
                delayedNetwork = dispatched;
                throw new IllegalStateException("simulated lost create response");
            }
            return create("network", dispatched, null);
        }

        void materializeDelayedNetwork() {
            DockerResourceRecord dispatched = java.util.Objects.requireNonNull(delayedNetwork);
            resources.put("network-" + dispatched.logicalName(), dispatched);
            delayedNetwork = null;
        }

        @Override
        public DockerCreatedResource createVolume(DockerResourceRecord dispatched) {
            return create("volume", dispatched, null);
        }

        @Override
        public DockerCreatedResource createContainer(
                DockerResourceRecord dispatched, DockerContainerSpec specification) {
            DockerCreatedResource created = create("container", dispatched, specification);
            createdSpecifications.put(created.id(), specification);
            return created;
        }

        private DockerCreatedResource create(
                String prefix, DockerResourceRecord dispatched, DockerContainerSpec specification) {
            String id = prefix + "-" + dispatched.logicalName();
            resources.put(id, dispatched);
            calls.add("create:" + id);
            return created(id, dispatched);
        }

        private static DockerCreatedResource created(String id, DockerResourceRecord dispatched) {
            return dispatched.type() == DockerResourceType.VOLUME
                    ? DockerCreatedResource.identified(id, "created-" + dispatched.ownershipToken())
                    : DockerCreatedResource.withImmutableId(id);
        }

        @Override
        public DockerContainerView inspectContainer(
                DockerResourceRecord active, DockerContainerSpec specification) {
            String id = active.engineId().orElseThrow();
            requireOwned(id, active);
            assertThat(createdSpecifications.get(id)).isEqualTo(specification);
            DockerContainerView view = new DockerContainerView(
                    id,
                    active.logicalName(),
                    active.logicalName(),
                    specification.image(),
                    containersRunning ? "running" : "exited",
                    containersRunning,
                    containersRunning ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(0),
                    specification.healthCheckRequired() ? healthStatus : DockerHealthStatus.NONE,
                    List.of());
            afterInspect.run();
            return view;
        }

        @Override
        public DockerContainerView inspectContainerSnapshot(DockerResourceRecord active) {
            String id = active.engineId().orElseThrow();
            DockerContainerSpec specification = createdSpecifications.get(id);
            if (specification == null) {
                throw new IllegalStateException("No container specification is available.");
            }
            return inspectContainer(active, specification);
        }

        @Override
        public void startContainer(
                DockerResourceRecord active, DockerContainerSpec specification) {
            requireOwned(active.engineId().orElseThrow(), active);
            assertThat(createdSpecifications.get(active.engineId().orElseThrow()))
                    .isEqualTo(specification);
            calls.add("start:" + active.engineId().orElseThrow());
            if (startFailure != null) {
                throw startFailure;
            }
            afterStart.run();
        }

        @Override
        public void stopContainer(DockerResourceRecord active, Duration timeout) {
            requireOwned(active.engineId().orElseThrow(), active);
            calls.add("stop:" + active.engineId().orElseThrow());
        }

        @Override
        public void removeContainer(DockerResourceRecord active) {
            String id = active.engineId().orElseThrow();
            requireOwned(id, active);
            resources.remove(id);
            calls.add("remove:" + id);
        }

        @Override
        public void removeNetwork(DockerResourceRecord active) {
            String id = active.engineId().orElseThrow();
            requireOwned(id, active);
            resources.remove(id);
            calls.add("remove:" + id);
        }

        @Override
        public void verifyVolume(DockerResourceRecord active) {
            requireOwned(active.engineId().orElseThrow(), active);
            if (failVolumeVerification) {
                throw new IllegalStateException("simulated volume verification failure");
            }
            calls.add("verify:" + active.engineId().orElseThrow());
        }

        private void requireOwned(String id, DockerResourceRecord active) {
            DockerResourceRecord reserved = resources.get(id);
            if (reserved == null || !reserved.ownershipToken().equals(active.ownershipToken())) {
                throw new DockerOwnershipException("not owned");
            }
        }
    }

    private static final class BlockingClock extends Clock {
        private final AtomicBoolean blockNext = new AtomicBoolean();
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private void blockNextInstant() {
            blockNext.set(true);
        }

        private boolean awaitBlocked() throws InterruptedException {
            return blocked.await(2, TimeUnit.SECONDS);
        }

        private void release() {
            released.countDown();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported by this test clock.");
            }
            return this;
        }

        @Override
        public Instant instant() {
            if (blockNext.compareAndSet(true, false)) {
                blocked.countDown();
                try {
                    if (!released.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("The test clock was not released.");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("The test clock was interrupted.", exception);
                }
            }
            return NOW;
        }
    }

    private static final class FakeRuntimeMonitor implements DockerRuntimeMonitorPort {
        private long runningRevision;
        private Consumer<RuntimeFailure> failureHandler;
        private boolean failRegistration;
        private boolean blockCancellation;
        private final CountDownLatch cancellationEntered = new CountDownLatch(1);
        private final CountDownLatch cancellationReleased = new CountDownLatch(1);

        private void blockCancellation() {
            blockCancellation = true;
        }

        private boolean awaitCancellation() throws InterruptedException {
            return cancellationEntered.await(2, TimeUnit.SECONDS);
        }

        private void releaseCancellation() {
            cancellationReleased.countDown();
        }

        @Override
        public void watch(
                String labId,
                long runningRevision,
                List<DockerReadinessWaiter.ServiceProbe> probes,
                Consumer<RuntimeFailure> failureHandler) {
            if (failRegistration) {
                throw new IllegalStateException("simulated monitor registration failure");
            }
            this.runningRevision = runningRevision;
            this.failureHandler = failureHandler;
        }

        @Override
        public void cancel(String labId) {
            if (blockCancellation) {
                cancellationEntered.countDown();
                try {
                    if (!cancellationReleased.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("The test monitor cancellation was not released.");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("The test monitor cancellation was interrupted.", exception);
                }
            }
            // Preserve the old handler so the stale-revision guard can be tested.
        }

        @Override
        public void close() {
            // No background work.
        }
    }
}
