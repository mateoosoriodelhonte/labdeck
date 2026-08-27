package io.labdeck.lab;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

public record TestRunRecord(
        String id,
        String labId,
        long labRevision,
        String service,
        String testPlanSha256,
        Instant recordedAt,
        TestStatus status,
        TestOutcomeReason outcomeReason,
        Duration duration,
        OptionalInt exitCode,
        StoredOutput stdout,
        StoredOutput stderr) {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final Duration MAX_DURATION = Duration.ofDays(1);

    public TestRunRecord {
        if (id == null || !ID.matcher(id).matches() || labId == null || !ID.matcher(labId).matches()) {
            throw new IllegalArgumentException("The test run or lab ID is not valid.");
        }
        if (labRevision < 0) {
            throw new IllegalArgumentException("The tested lab revision is not valid.");
        }
        if (service == null || !service.matches("[a-z][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("The tested service is not valid.");
        }
        if (testPlanSha256 == null || !testPlanSha256.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException("The test plan digest is not valid.");
        }
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (recordedAt.isBefore(Instant.EPOCH)) {
            throw new IllegalArgumentException("The test timestamp is not valid.");
        }
        requireEpochMilliseconds(recordedAt, "test timestamp");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(outcomeReason, "outcomeReason");
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException("The test duration is not valid.");
        }
        exitCode = exitCode == null ? OptionalInt.empty() : exitCode;
        validateStatusAndExitCode(status, exitCode);
        validateStatusAndReason(status, outcomeReason);
        if (outcomeReason == TestOutcomeReason.LEGACY
                && (labRevision != 0
                        || !service.equals("legacy")
                        || !testPlanSha256.equals(
                                "sha256:0000000000000000000000000000000000000000000000000000000000000000"))) {
            throw new IllegalArgumentException("Legacy test provenance must use the migration sentinel.");
        }
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        if (stdout.utf8Bytes() + stderr.utf8Bytes() > StoredOutput.MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("Stored test output exceeds the combined UTF-8 byte limit.");
        }
    }

    public static TestRunRecord bounded(
            String id,
            String labId,
            long labRevision,
            String service,
            String testPlanSha256,
            Instant recordedAt,
            TestStatus status,
            TestOutcomeReason outcomeReason,
            Duration duration,
            OptionalInt exitCode,
            TestOutputSanitizer sanitizer,
            String stdout,
            String stderr) {
        return bounded(
                id,
                labId,
                labRevision,
                service,
                testPlanSha256,
                recordedAt,
                status,
                outcomeReason,
                duration,
                exitCode,
                sanitizer,
                stdout,
                stderr,
                false,
                false);
    }

    public static TestRunRecord bounded(
            String id,
            String labId,
            long labRevision,
            String service,
            String testPlanSha256,
            Instant recordedAt,
            TestStatus status,
            TestOutcomeReason outcomeReason,
            Duration duration,
            OptionalInt exitCode,
            TestOutputSanitizer sanitizer,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated) {
        Objects.requireNonNull(sanitizer, "sanitizer");
        int halfLimit = StoredOutput.MAX_UTF8_BYTES / 2;
        StoredOutput boundedStdout = StoredOutput.bounded(
                stdout, halfLimit, sanitizer, stdoutTruncated);
        StoredOutput boundedStderr = StoredOutput.bounded(
                stderr, halfLimit, sanitizer, stderrTruncated);
        if (!boundedStdout.truncated()) {
            boundedStderr = StoredOutput.bounded(
                    stderr,
                    StoredOutput.MAX_UTF8_BYTES - boundedStdout.utf8Bytes(),
                    sanitizer,
                    stderrTruncated);
        } else if (!boundedStderr.truncated()) {
            boundedStdout = StoredOutput.bounded(
                    stdout,
                    StoredOutput.MAX_UTF8_BYTES - boundedStderr.utf8Bytes(),
                    sanitizer,
                    stdoutTruncated);
        }
        return new TestRunRecord(
                id,
                labId,
                labRevision,
                service,
                testPlanSha256,
                recordedAt,
                status,
                outcomeReason,
                duration,
                exitCode,
                boundedStdout,
                boundedStderr);
    }

    public boolean outputTruncated() {
        return stdout.truncated() || stderr.truncated();
    }

    private static void validateStatusAndExitCode(TestStatus status, OptionalInt exitCode) {
        if (status == TestStatus.PASSED && (exitCode.isEmpty() || exitCode.getAsInt() != 0)) {
            throw new IllegalArgumentException("A passed test must have exit code 0.");
        }
        if (status == TestStatus.FAILED && (exitCode.isEmpty() || exitCode.getAsInt() == 0)) {
            throw new IllegalArgumentException("A failed test must have a non-zero exit code.");
        }
    }

    private static void validateStatusAndReason(TestStatus status, TestOutcomeReason reason) {
        boolean valid = switch (status) {
            case PASSED -> reason == TestOutcomeReason.EXIT_ZERO || reason == TestOutcomeReason.LEGACY;
            case FAILED -> reason == TestOutcomeReason.NON_ZERO_EXIT || reason == TestOutcomeReason.LEGACY;
            case CANCELLED -> Set.of(
                            TestOutcomeReason.USER_CANCELLED,
                            TestOutcomeReason.LAB_STOPPED,
                            TestOutcomeReason.APPLICATION_SHUTDOWN,
                            TestOutcomeReason.LEGACY)
                    .contains(reason);
            case TIMED_OUT -> reason == TestOutcomeReason.TIMEOUT || reason == TestOutcomeReason.LEGACY;
            case ERROR -> Set.of(
                            TestOutcomeReason.SERVICE_NOT_ACTIVE,
                            TestOutcomeReason.DOCKER_ERROR,
                            TestOutcomeReason.RESULT_UNAVAILABLE,
                            TestOutcomeReason.LAB_CHANGED,
                            TestOutcomeReason.LEGACY)
                    .contains(reason);
        };
        if (!valid) {
            throw new IllegalArgumentException("The test outcome reason does not match its status.");
        }
    }

    private static void requireEpochMilliseconds(Instant value, String label) {
        try {
            value.toEpochMilli();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("The " + label + " is outside the storage range.", exception);
        }
    }

    @Override
    public String toString() {
        return "TestRunRecord[id=" + id
                + ", labId=" + labId
                + ", labRevision=" + labRevision
                + ", service=" + service
                + ", testPlanSha256=" + testPlanSha256
                + ", recordedAt=" + recordedAt
                + ", status=" + status
                + ", outcomeReason=" + outcomeReason
                + ", duration=" + duration
                + ", exitCode=" + exitCode
                + ", stdoutBytes=" + stdout.utf8Bytes()
                + ", stderrBytes=" + stderr.utf8Bytes()
                + ", outputTruncated=" + outputTruncated() + "]";
    }
}
