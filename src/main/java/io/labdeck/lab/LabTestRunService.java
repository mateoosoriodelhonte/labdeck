package io.labdeck.lab;

import io.labdeck.docker.CancellationToken;
import io.labdeck.docker.DockerActiveServiceNotFoundException;
import io.labdeck.docker.DockerLabLifecycle;
import io.labdeck.docker.DockerLabTestResult;
import io.labdeck.docker.DockerTestCancelCause;
import io.labdeck.docker.DockerTestExecutionResult;
import io.labdeck.docker.DockerTestExecutionState;
import io.labdeck.docker.DockerTestStartException;
import io.labdeck.docker.DockerTestTerminationException;
import io.labdeck.manifest.ManifestPlan;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LabTestRunService implements AutoCloseable {

    private static final int MAX_ACTIVE_RUNS = 2;
    private static final long SHUTDOWN_GRACE_SECONDS = 30;
    private static final long FORCED_SHUTDOWN_GRACE_SECONDS = 5;

    private final TestRunRepository tests;
    private final DockerLabLifecycle lifecycle;
    private final Clock clock;
    private final LongSupplier ticker;
    private final Supplier<String> identifier;
    private final ThreadPoolExecutor executor;
    private final Object reservationLock = new Object();
    private final ConcurrentHashMap<String, ActiveRun> activeById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActiveRun> activeByLab = new ConcurrentHashMap<>();

    @Autowired
    public LabTestRunService(TestRunRepository tests, DockerLabLifecycle lifecycle) {
        this(
                tests,
                lifecycle,
                Clock.systemUTC(),
                System::nanoTime,
                () -> "test-" + UUID.randomUUID());
    }

    LabTestRunService(
            TestRunRepository tests,
            DockerLabLifecycle lifecycle,
            Clock clock,
            LongSupplier ticker,
            Supplier<String> identifier) {
        this.tests = Objects.requireNonNull(tests, "tests");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.identifier = Objects.requireNonNull(identifier, "identifier");
        this.executor = new ThreadPoolExecutor(
                0,
                MAX_ACTIVE_RUNS,
                1,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                Thread.ofVirtual().name("labdeck-test-run-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public TestRunSnapshot start(LabRecord lab, ManifestPlan plan) {
        Objects.requireNonNull(lab, "lab");
        Objects.requireNonNull(plan, "plan");
        var definition = plan.tests().orElseThrow(() -> new TestRunCoordinatorException(
                TestRunCoordinatorException.Reason.TEST_NOT_CONFIGURED));
        ActiveRun run;
        synchronized (reservationLock) {
            if (activeByLab.containsKey(lab.id())) {
                throw new TestRunCoordinatorException(
                        TestRunCoordinatorException.Reason.TEST_ALREADY_RUNNING);
            }
            if (activeById.size() >= MAX_ACTIVE_RUNS) {
                throw new TestRunCoordinatorException(
                        TestRunCoordinatorException.Reason.PROCESS_LIMIT_REACHED);
            }
            String id = requireIdentifier(identifier.get());
            run = new ActiveRun(
                    id,
                    lab,
                    plan,
                    definition.service(),
                    testPlanDigest(plan),
                    clock.instant().truncatedTo(ChronoUnit.MILLIS),
                    ticker.getAsLong());
            activeById.put(id, run);
            activeByLab.put(lab.id(), run);
            try {
                executor.execute(() -> execute(run));
            } catch (RejectedExecutionException failure) {
                activeById.remove(id, run);
                activeByLab.remove(lab.id(), run);
                throw new TestRunCoordinatorException(
                        TestRunCoordinatorException.Reason.RUNNER_UNAVAILABLE);
            }
        }
        return snapshot(run);
    }

    public TestRunSnapshot find(String labId, String runId) {
        ActiveRun active = activeById.get(runId);
        if (active != null && active.lab().id().equals(labId)) {
            return snapshot(active);
        }
        TestRunRecord completed = tests.findById(runId)
                .filter(run -> run.labId().equals(labId))
                .orElseThrow(() -> new TestRunCoordinatorException(
                        TestRunCoordinatorException.Reason.TEST_RUN_NOT_FOUND));
        return snapshot(completed);
    }

    public Optional<TestRunSnapshot> findActive(String labId) {
        if (labId == null || !labId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("The lab ID is not valid.");
        }
        return Optional.ofNullable(activeByLab.get(labId)).map(this::snapshot);
    }

    public TestRunSnapshot cancel(String labId, String runId) {
        ActiveRun run = activeById.get(runId);
        if (run == null || !run.lab().id().equals(labId)) {
            throw new TestRunCoordinatorException(
                    TestRunCoordinatorException.Reason.TEST_RUN_NOT_FOUND);
        }
        TestRunRecord terminal = run.terminalResult().get();
        if (terminal != null) {
            return snapshot(run);
        }
        run.cancel(DockerTestCancelCause.USER_CANCELLED);
        lifecycle.cancelTest(labId);
        return snapshot(run);
    }

    private void execute(ActiveRun run) {
        TestRunRecord completed;
        try {
            DockerLabTestResult result = lifecycle.executeTest(
                    run.lab(), run.plan(), run.cancellation());
            completed = record(run, result.execution(), cancelCause(run, result));
        } catch (DockerActiveServiceNotFoundException failure) {
            completed = error(run, TestOutcomeReason.SERVICE_NOT_ACTIVE);
        } catch (DockerTestStartException failure) {
            completed = error(run, TestOutcomeReason.LAB_CHANGED);
        } catch (DockerTestTerminationException failure) {
            completed = error(run, TestOutcomeReason.RESULT_UNAVAILABLE);
        } catch (RuntimeException failure) {
            completed = error(run, TestOutcomeReason.DOCKER_ERROR);
        }
        run.terminalResult().set(completed);
        try {
            tests.append(completed);
        } catch (RuntimeException persistenceFailure) {
            run.terminalResult().set(error(run, TestOutcomeReason.RESULT_UNAVAILABLE));
            run.persistenceFailed().set(true);
            return;
        }
        synchronized (reservationLock) {
            activeById.remove(run.id(), run);
            activeByLab.remove(run.lab().id(), run);
        }
    }

    private TestRunRecord record(
            ActiveRun run,
            DockerTestExecutionResult execution,
            Optional<DockerTestCancelCause> cancellation) {
        TestStatus status;
        TestOutcomeReason reason;
        OptionalInt exitCode = execution.exitCode();
        switch (execution.state()) {
            case COMPLETED -> {
                status = exitCode.orElseThrow() == 0 ? TestStatus.PASSED : TestStatus.FAILED;
                reason = status == TestStatus.PASSED
                        ? TestOutcomeReason.EXIT_ZERO : TestOutcomeReason.NON_ZERO_EXIT;
            }
            case CANCELLED -> {
                status = TestStatus.CANCELLED;
                reason = switch (cancellation.orElse(DockerTestCancelCause.USER_CANCELLED)) {
                    case USER_CANCELLED -> TestOutcomeReason.USER_CANCELLED;
                    case LAB_STOPPED -> TestOutcomeReason.LAB_STOPPED;
                    case APPLICATION_SHUTDOWN -> TestOutcomeReason.APPLICATION_SHUTDOWN;
                };
            }
            case TIMED_OUT -> {
                status = TestStatus.TIMED_OUT;
                reason = TestOutcomeReason.TIMEOUT;
            }
            case ERROR -> {
                status = TestStatus.ERROR;
                reason = TestOutcomeReason.DOCKER_ERROR;
            }
            default -> throw new IllegalStateException("Unknown Docker test state.");
        }
        return boundedRecord(
                run,
                status,
                reason,
                exitCode,
                execution.stdout(),
                execution.stderr(),
                execution.stdoutTruncated(),
                execution.stderrTruncated());
    }

    private TestRunRecord error(ActiveRun run, TestOutcomeReason reason) {
        return boundedRecord(
                run,
                TestStatus.ERROR,
                reason,
                OptionalInt.empty(),
                "",
                "",
                false,
                false);
    }

    private TestRunRecord boundedRecord(
            ActiveRun run,
            TestStatus status,
            TestOutcomeReason reason,
            OptionalInt exitCode,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated) {
        Instant completedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Duration duration = Duration.ofNanos(Math.max(0L, ticker.getAsLong() - run.startedNanos()));
        List<String> sensitiveValues = run.plan().services().stream()
                .flatMap(service -> service.definition().environment().values().stream())
                .distinct()
                .toList();
        TestOutputSanitizer sanitizer = TestOutputSanitizer.forLab(
                run.lab().workspace(), sensitiveValues);
        return TestRunRecord.bounded(
                run.id(),
                run.lab().id(),
                run.lab().revision(),
                run.service(),
                run.testPlanSha256(),
                completedAt,
                status,
                reason,
                duration,
                exitCode,
                sanitizer,
                stdout,
                stderr,
                stdoutTruncated,
                stderrTruncated);
    }

    private static Optional<DockerTestCancelCause> cancelCause(
            ActiveRun run, DockerLabTestResult result) {
        return run.cancellation().cause().or(() -> result.cancelCause());
    }

    private TestRunSnapshot snapshot(ActiveRun run) {
        TestRunRecord terminal = run.terminalResult().get();
        if (terminal != null) {
            if (run.persistenceFailed().get()) {
                return snapshot(terminal);
            }
            return new TestRunSnapshot(
                    run.id(),
                    run.lab().id(),
                    run.lab().revision(),
                    run.service(),
                    run.testPlanSha256(),
                    run.startedAt(),
                    terminal.recordedAt(),
                    "PERSISTING",
                    null,
                    terminal.duration().toMillis(),
                    null,
                    "",
                    "",
                    false,
                    false,
                    false);
        }
        long durationMillis = Math.max(
                0L,
                TimeUnit.NANOSECONDS.toMillis(ticker.getAsLong() - run.startedNanos()));
        return new TestRunSnapshot(
                run.id(),
                run.lab().id(),
                run.lab().revision(),
                run.service(),
                run.testPlanSha256(),
                run.startedAt(),
                null,
                run.cancellation().cause().isPresent() ? "CANCELLING" : "RUNNING",
                null,
                durationMillis,
                null,
                "",
                "",
                false,
                false,
                run.cancellation().cause().isEmpty());
    }

    private static TestRunSnapshot snapshot(TestRunRecord run) {
        Instant startedAt = run.recordedAt().minus(run.duration());
        return new TestRunSnapshot(
                run.id(),
                run.labId(),
                run.labRevision(),
                run.service(),
                run.testPlanSha256(),
                startedAt,
                run.recordedAt(),
                run.status().name(),
                run.outcomeReason().name(),
                run.duration().toMillis(),
                run.exitCode().isPresent() ? run.exitCode().getAsInt() : null,
                run.stdout().text(),
                run.stderr().text(),
                run.stdout().truncated(),
                run.stderr().truncated(),
                false);
    }

    static String testPlanDigest(ManifestPlan plan) {
        var test = plan.tests().orElseThrow();
        StringBuilder canonical = new StringBuilder();
        append(canonical, test.service());
        append(canonical, Long.toString(test.timeout().toMillis()));
        append(canonical, Integer.toString(test.command().size()));
        test.command().forEach(argument -> append(canonical, argument));
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", failure);
        }
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }

    private static String requireIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalStateException("The generated test run identifier is not valid.");
        }
        return value;
    }

    @Override
    @PreDestroy
    public void close() {
        List<ActiveRun> active = List.copyOf(activeById.values());
        active.stream().filter(run -> run.terminalResult().get() == null).forEach(run -> {
            run.cancel(DockerTestCancelCause.APPLICATION_SHUTDOWN);
            lifecycle.cancelTest(run.lab().id());
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(FORCED_SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private record ActiveRun(
            String id,
            LabRecord lab,
            ManifestPlan plan,
            String service,
            String testPlanSha256,
            Instant startedAt,
            long startedNanos,
            RunCancellation cancellation,
            AtomicReference<TestRunRecord> terminalResult,
            AtomicBoolean persistenceFailed) {

        private ActiveRun(
                String id,
                LabRecord lab,
                ManifestPlan plan,
                String service,
                String testPlanSha256,
                Instant startedAt,
                long startedNanos) {
            this(
                    id,
                    lab,
                    plan,
                    service,
                    testPlanSha256,
                    startedAt,
                    startedNanos,
                    new RunCancellation(),
                    new AtomicReference<>(),
                    new AtomicBoolean());
        }

        private void cancel(DockerTestCancelCause cause) {
            cancellation.cancel(cause);
        }
    }

    private static final class RunCancellation implements CancellationToken {
        private final AtomicReference<DockerTestCancelCause> cause = new AtomicReference<>();

        @Override
        public boolean isCancellationRequested() {
            return cause.get() != null;
        }

        private void cancel(DockerTestCancelCause value) {
            cause.compareAndSet(null, Objects.requireNonNull(value, "value"));
        }

        private Optional<DockerTestCancelCause> cause() {
            return Optional.ofNullable(cause.get());
        }
    }
}
