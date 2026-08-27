package io.labdeck.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.labdeck.docker.DockerLabLifecycle;
import io.labdeck.docker.DockerLabTestResult;
import io.labdeck.docker.DockerTestCancelCause;
import io.labdeck.docker.DockerTestExecutionResult;
import io.labdeck.docker.DockerTestExecutionState;
import io.labdeck.manifest.ManifestPlan;
import io.labdeck.manifest.ManifestPlanCompiler;
import io.labdeck.manifest.RestrictedManifestParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LabTestRunServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-27T17:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsACompletedResultWithProvenanceAndAllManifestValuesRedacted() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        String secret = "manifest-secret-91c743";
        ManifestPlan plan = plan(secret);
        LabRecord lab = runningLab(workspace, "lab-1");
        MemoryTests tests = new MemoryTests();
        DockerLabLifecycle lifecycle = mock(DockerLabLifecycle.class);
        when(lifecycle.executeTest(any(), any(), any())).thenReturn(new DockerLabTestResult(
                new DockerTestExecutionResult(
                        DockerTestExecutionState.COMPLETED,
                        OptionalInt.of(0),
                        "workspace=" + workspace + " token=" + secret + "\u001b[31m",
                        "",
                        false,
                        false),
                Optional.empty()));
        LabTestRunService service = service(tests, lifecycle);

        try {
            TestRunSnapshot started = service.start(lab, plan);
            TestRunSnapshot completed = awaitCompleted(service, lab.id(), started.id());

            assertThat(completed.status()).isEqualTo("PASSED");
            assertThat(completed.outcomeReason()).isEqualTo("EXIT_ZERO");
            assertThat(completed.labRevision()).isEqualTo(2);
            assertThat(completed.service()).isEqualTo("app");
            assertThat(completed.testPlanSha256()).matches("sha256:[a-f0-9]{64}");
            assertThat(completed.stdout())
                    .contains(TestOutputSanitizer.REDACTION)
                    .doesNotContain(secret, workspace.toString(), "\u001b");
            assertThat(tests.findById(started.id())).isPresent();
        } finally {
            service.close();
        }
    }

    @Test
    void cancellationIsVisibleAndPersistsOnlyOneCancelledResult() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("cancel-workspace"));
        ManifestPlan plan = plan("secret");
        LabRecord lab = runningLab(workspace, "lab-1");
        MemoryTests tests = new MemoryTests();
        DockerLabLifecycle lifecycle = mock(DockerLabLifecycle.class);
        CountDownLatch entered = new CountDownLatch(1);
        when(lifecycle.executeTest(any(), any(), any())).thenAnswer(invocation -> {
            io.labdeck.docker.CancellationToken cancellation = invocation.getArgument(2);
            entered.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!cancellation.isCancellationRequested() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            return new DockerLabTestResult(
                    new DockerTestExecutionResult(
                            DockerTestExecutionState.CANCELLED,
                            OptionalInt.empty(),
                            "partial",
                            "",
                            false,
                            false),
                    Optional.of(DockerTestCancelCause.USER_CANCELLED));
        });
        LabTestRunService service = service(tests, lifecycle);

        try {
            TestRunSnapshot started = service.start(lab, plan);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            TestRunSnapshot cancelling = service.cancel(lab.id(), started.id());
            TestRunSnapshot completed = awaitCompleted(service, lab.id(), started.id());

            assertThat(cancelling.status()).isEqualTo("CANCELLING");
            assertThat(cancelling.canCancel()).isFalse();
            assertThat(completed.status()).isEqualTo("CANCELLED");
            assertThat(completed.outcomeReason()).isEqualTo("USER_CANCELLED");
            assertThat(tests.runs).hasSize(1);
        } finally {
            service.close();
        }
    }

    @Test
    void allowsTwoProcessRunsButRejectsAThirdAndASecondRunForOneLab() throws Exception {
        ManifestPlan plan = plan("secret");
        MemoryTests tests = new MemoryTests();
        DockerLabLifecycle lifecycle = mock(DockerLabLifecycle.class);
        CountDownLatch release = new CountDownLatch(1);
        when(lifecycle.executeTest(any(), any(), any())).thenAnswer(invocation -> {
            release.await(2, TimeUnit.SECONDS);
            return new DockerLabTestResult(
                    new DockerTestExecutionResult(
                            DockerTestExecutionState.COMPLETED,
                            OptionalInt.of(0),
                            "",
                            "",
                            false,
                            false),
                    Optional.empty());
        });
        LabTestRunService service = service(tests, lifecycle);
        LabRecord first = runningLab(
                Files.createDirectories(temporaryDirectory.resolve("one")), "lab-1");
        LabRecord second = runningLab(
                Files.createDirectories(temporaryDirectory.resolve("two")), "lab-2");
        LabRecord third = runningLab(
                Files.createDirectories(temporaryDirectory.resolve("three")), "lab-3");

        try {
            service.start(first, plan);
            assertThatThrownBy(() -> service.start(first, plan))
                    .isInstanceOfSatisfying(TestRunCoordinatorException.class, failure ->
                            assertThat(failure.reason()).isEqualTo(
                                    TestRunCoordinatorException.Reason.TEST_ALREADY_RUNNING));
            service.start(second, plan);
            assertThatThrownBy(() -> service.start(third, plan))
                    .isInstanceOfSatisfying(TestRunCoordinatorException.class, failure ->
                            assertThat(failure.reason()).isEqualTo(
                                    TestRunCoordinatorException.Reason.PROCESS_LIMIT_REACHED));
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void closeWaitsForTheCancelledRunToBePersisted() throws Exception {
        ManifestPlan plan = plan("secret");
        MemoryTests tests = new MemoryTests();
        DockerLabLifecycle lifecycle = mock(DockerLabLifecycle.class);
        CountDownLatch entered = new CountDownLatch(1);
        when(lifecycle.executeTest(any(), any(), any())).thenAnswer(invocation -> {
            io.labdeck.docker.CancellationToken cancellation = invocation.getArgument(2);
            entered.countDown();
            while (!cancellation.isCancellationRequested()) {
                Thread.onSpinWait();
            }
            return new DockerLabTestResult(
                    new DockerTestExecutionResult(
                            DockerTestExecutionState.CANCELLED,
                            OptionalInt.empty(),
                            "partial",
                            "",
                            false,
                            false),
                    Optional.of(DockerTestCancelCause.APPLICATION_SHUTDOWN));
        });
        LabTestRunService service = service(tests, lifecycle);
        LabRecord lab = runningLab(
                Files.createDirectories(temporaryDirectory.resolve("shutdown")), "lab-1");

        TestRunSnapshot started = service.start(lab, plan);
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

        service.close();

        assertThat(tests.findById(started.id()))
                .get()
                .extracting(TestRunRecord::status, TestRunRecord::outcomeReason)
                .containsExactly(TestStatus.CANCELLED, TestOutcomeReason.APPLICATION_SHUTDOWN);
    }

    @Test
    void persistenceFailureKeepsTheRunReservedAndExposesAResultUnavailableError() throws Exception {
        ManifestPlan plan = plan("secret");
        TestRunRepository tests = mock(TestRunRepository.class);
        when(tests.findById(any())).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated disk failure"))
                .when(tests).append(any());
        DockerLabLifecycle lifecycle = mock(DockerLabLifecycle.class);
        when(lifecycle.executeTest(any(), any(), any())).thenReturn(new DockerLabTestResult(
                new DockerTestExecutionResult(
                        DockerTestExecutionState.COMPLETED,
                        OptionalInt.of(0),
                        "passed",
                        "",
                        false,
                        false),
                Optional.empty()));
        LabTestRunService service = service(tests, lifecycle);
        LabRecord lab = runningLab(
                Files.createDirectories(temporaryDirectory.resolve("persistence-failure")), "lab-1");

        try {
            TestRunSnapshot started = service.start(lab, plan);
            TestRunSnapshot failed = awaitCompleted(service, lab.id(), started.id());

            assertThat(failed.status()).isEqualTo("ERROR");
            assertThat(failed.outcomeReason()).isEqualTo("RESULT_UNAVAILABLE");
            assertThat(failed.canCancel()).isFalse();
            assertThatThrownBy(() -> service.start(lab, plan))
                    .isInstanceOfSatisfying(TestRunCoordinatorException.class, failure ->
                            assertThat(failure.reason()).isEqualTo(
                                    TestRunCoordinatorException.Reason.TEST_ALREADY_RUNNING));
            verify(tests).append(any());
        } finally {
            service.close();
        }
    }

    @Test
    void reportsPersistingWithoutClaimingThatASlowSuccessfulWriteFailed() throws Exception {
        ManifestPlan plan = plan("secret");
        BlockingTests tests = new BlockingTests();
        DockerLabLifecycle lifecycle = mock(DockerLabLifecycle.class);
        when(lifecycle.executeTest(any(), any(), any())).thenReturn(new DockerLabTestResult(
                new DockerTestExecutionResult(
                        DockerTestExecutionState.COMPLETED,
                        OptionalInt.of(0),
                        "passed",
                        "",
                        false,
                        false),
                Optional.empty()));
        LabTestRunService service = service(tests, lifecycle);
        LabRecord lab = runningLab(
                Files.createDirectories(temporaryDirectory.resolve("slow-persistence")), "lab-1");

        try {
            TestRunSnapshot started = service.start(lab, plan);
            assertThat(tests.appendEntered.await(1, TimeUnit.SECONDS)).isTrue();

            TestRunSnapshot persisting = service.findActive(lab.id()).orElseThrow();

            assertThat(persisting.id()).isEqualTo(started.id());
            assertThat(persisting.status()).isEqualTo("PERSISTING");
            assertThat(persisting.outcomeReason()).isNull();
            assertThat(persisting.canCancel()).isFalse();
            tests.releaseAppend.countDown();
            assertThat(awaitCompleted(service, lab.id(), started.id()).status()).isEqualTo("PASSED");
        } finally {
            tests.releaseAppend.countDown();
            service.close();
        }
    }

    private LabTestRunService service(TestRunRepository tests, DockerLabLifecycle lifecycle) {
        AtomicInteger ids = new AtomicInteger();
        return new LabTestRunService(
                tests,
                lifecycle,
                Clock.fixed(NOW, ZoneOffset.UTC),
                System::nanoTime,
                () -> "test-" + ids.incrementAndGet());
    }

    private static TestRunSnapshot awaitCompleted(
            LabTestRunService service, String labId, String runId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        TestRunSnapshot latest;
        do {
            latest = service.find(labId, runId);
            if (!List.of("RUNNING", "CANCELLING", "PERSISTING").contains(latest.status())) {
                return latest;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return latest;
    }

    private static LabRecord runningLab(Path workspace, String id) {
        return new LabRecord(
                id,
                "project-" + id,
                "Test lab",
                1,
                workspace,
                LabState.RUNNING,
                2,
                NOW,
                NOW);
    }

    private static ManifestPlan plan(String secret) {
        return new ManifestPlanCompiler().compile(new RestrictedManifestParser().parse("""
                version: 1
                name: Test lab
                workspace:
                  mount: /workspace
                services:
                  app:
                    image: busybox:1.37
                    command: ["sleep", "60"]
                    environment:
                      TEST_SECRET: %s
                tests:
                  service: app
                  command: ["true"]
                  timeout: 5s
                """.formatted(secret)));
    }

    private static final class MemoryTests implements TestRunRepository {
        private final Map<String, TestRunRecord> runs = new ConcurrentHashMap<>();

        @Override
        public void append(TestRunRecord testRun) {
            if (runs.putIfAbsent(testRun.id(), testRun) != null) {
                throw new IllegalStateException("duplicate test run");
            }
        }

        @Override
        public Optional<TestRunRecord> findById(String id) {
            return Optional.ofNullable(runs.get(id));
        }

        @Override
        public List<TestRunRecord> findRecentByLab(String labId, int limit) {
            return new ArrayList<>(runs.values()).stream()
                    .filter(run -> run.labId().equals(labId))
                    .limit(limit)
                    .toList();
        }
    }

    private static final class BlockingTests implements TestRunRepository {
        private final MemoryTests delegate = new MemoryTests();
        private final CountDownLatch appendEntered = new CountDownLatch(1);
        private final CountDownLatch releaseAppend = new CountDownLatch(1);

        @Override
        public void append(TestRunRecord testRun) {
            appendEntered.countDown();
            try {
                if (!releaseAppend.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release test persistence");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test persistence was interrupted", failure);
            }
            delegate.append(testRun);
        }

        @Override
        public Optional<TestRunRecord> findById(String id) {
            return delegate.findById(id);
        }

        @Override
        public List<TestRunRecord> findRecentByLab(String labId, int limit) {
            return delegate.findRecentByLab(labId, limit);
        }
    }
}
