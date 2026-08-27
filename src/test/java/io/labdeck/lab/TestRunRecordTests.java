package io.labdeck.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class TestRunRecordTests {

    private static final String PLAN_SHA256 = "sha256:" + "a".repeat(64);
    private static final TestOutputSanitizer SANITIZER =
            TestOutputSanitizer.forLab(Path.of("/tmp/test-workspace"), List.of());

    @Test
    void boundsCombinedStandardOutputAndError() {
        TestRunRecord result = TestRunRecord.bounded(
                "run-1",
                "lab-1",
                2,
                "app",
                PLAN_SHA256,
                Instant.ofEpochMilli(1_000),
                TestStatus.ERROR,
                TestOutcomeReason.DOCKER_ERROR,
                Duration.ofSeconds(2),
                OptionalInt.empty(),
                SANITIZER,
                "student-output-secret",
                "🙂".repeat(20_000));

        assertThat(result.stdout().utf8Bytes() + result.stderr().utf8Bytes())
                .isLessThanOrEqualTo(StoredOutput.MAX_UTF8_BYTES);
        assertThat(result.stderr().truncated()).isTrue();
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.toString()).doesNotContain("student-output-secret", "🙂");
    }

    @Test
    void requiresExitCodesThatMatchPassAndFailResults() {
        assertThatThrownBy(() -> result(TestStatus.PASSED, OptionalInt.of(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(TestStatus.FAILED, OptionalInt.of(0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(result(TestStatus.PASSED, OptionalInt.of(0)).exitCode()).hasValue(0);
        assertThat(result(TestStatus.ERROR, OptionalInt.empty()).exitCode()).isEmpty();
    }

    @Test
    void rejectsAnAggregateOutputThatBypassesTheBoundedFactory() {
        StoredOutput first = StoredOutput.fromPersistence("a".repeat(40_000), false);
        StoredOutput second = StoredOutput.fromPersistence("b".repeat(40_000), false);

        assertThatThrownBy(() -> new TestRunRecord(
                        "run-1",
                        "lab-1",
                        2,
                        "app",
                        PLAN_SHA256,
                        Instant.EPOCH,
                        TestStatus.ERROR,
                        TestOutcomeReason.DOCKER_ERROR,
                        Duration.ZERO,
                        OptionalInt.empty(),
                        first,
                        second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("combined");
    }

    @Test
    void rejectsATimestampOutsideTheSQLiteEpochMillisecondRange() {
        assertThatThrownBy(() -> TestRunRecord.bounded(
                        "run-1",
                        "lab-1",
                        2,
                        "app",
                        PLAN_SHA256,
                        Instant.MAX,
                        TestStatus.ERROR,
                        TestOutcomeReason.DOCKER_ERROR,
                        Duration.ZERO,
                        OptionalInt.empty(),
                        SANITIZER,
                        "",
                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storage range");
    }

    @Test
    void acceptsLegacyResultsOnlyWithTheMigrationSentinel() {
        assertThatThrownBy(() -> TestRunRecord.bounded(
                        "run-1",
                        "lab-1",
                        2,
                        "app",
                        PLAN_SHA256,
                        Instant.EPOCH,
                        TestStatus.PASSED,
                        TestOutcomeReason.LEGACY,
                        Duration.ZERO,
                        OptionalInt.of(0),
                        SANITIZER,
                        "",
                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("migration sentinel");

        TestRunRecord migrated = TestRunRecord.bounded(
                "run-1",
                "lab-1",
                0,
                "legacy",
                "sha256:" + "0".repeat(64),
                Instant.EPOCH,
                TestStatus.PASSED,
                TestOutcomeReason.LEGACY,
                Duration.ZERO,
                OptionalInt.of(0),
                SANITIZER,
                "",
                "");

        assertThat(migrated.outcomeReason()).isEqualTo(TestOutcomeReason.LEGACY);
    }

    private static TestRunRecord result(TestStatus status, OptionalInt exitCode) {
        return TestRunRecord.bounded(
                "run-1",
                "lab-1",
                2,
                "app",
                PLAN_SHA256,
                Instant.EPOCH,
                status,
                reason(status),
                Duration.ZERO,
                exitCode,
                SANITIZER,
                "",
                "");
    }

    private static TestOutcomeReason reason(TestStatus status) {
        return switch (status) {
            case PASSED -> TestOutcomeReason.EXIT_ZERO;
            case FAILED -> TestOutcomeReason.NON_ZERO_EXIT;
            case ERROR -> TestOutcomeReason.DOCKER_ERROR;
            case CANCELLED -> TestOutcomeReason.USER_CANCELLED;
            case TIMED_OUT -> TestOutcomeReason.TIMEOUT;
        };
    }
}
