package io.labdeck.lab;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Pattern;

public record TestRunRecord(
        String id,
        String labId,
        Instant recordedAt,
        TestStatus status,
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
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (recordedAt.isBefore(Instant.EPOCH)) {
            throw new IllegalArgumentException("The test timestamp is not valid.");
        }
        requireEpochMilliseconds(recordedAt, "test timestamp");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException("The test duration is not valid.");
        }
        exitCode = exitCode == null ? OptionalInt.empty() : exitCode;
        validateStatusAndExitCode(status, exitCode);
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        if (stdout.utf8Bytes() + stderr.utf8Bytes() > StoredOutput.MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("Stored test output exceeds the combined UTF-8 byte limit.");
        }
    }

    public static TestRunRecord bounded(
            String id,
            String labId,
            Instant recordedAt,
            TestStatus status,
            Duration duration,
            OptionalInt exitCode,
            TestOutputSanitizer sanitizer,
            String stdout,
            String stderr) {
        Objects.requireNonNull(sanitizer, "sanitizer");
        int halfLimit = StoredOutput.MAX_UTF8_BYTES / 2;
        StoredOutput boundedStdout = StoredOutput.bounded(stdout, halfLimit, sanitizer);
        StoredOutput boundedStderr = StoredOutput.bounded(stderr, halfLimit, sanitizer);
        if (!boundedStdout.truncated()) {
            boundedStderr = StoredOutput.bounded(
                    stderr, StoredOutput.MAX_UTF8_BYTES - boundedStdout.utf8Bytes(), sanitizer);
        } else if (!boundedStderr.truncated()) {
            boundedStdout = StoredOutput.bounded(
                    stdout, StoredOutput.MAX_UTF8_BYTES - boundedStderr.utf8Bytes(), sanitizer);
        }
        return new TestRunRecord(
                id,
                labId,
                recordedAt,
                status,
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
                + ", recordedAt=" + recordedAt
                + ", status=" + status
                + ", duration=" + duration
                + ", exitCode=" + exitCode
                + ", stdoutBytes=" + stdout.utf8Bytes()
                + ", stderrBytes=" + stderr.utf8Bytes()
                + ", outputTruncated=" + outputTruncated() + "]";
    }
}
