package io.labdeck.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class TestRunRecordTests {

    @Test
    void boundsCombinedStandardOutputAndError() {
        TestRunRecord result = TestRunRecord.bounded(
                "run-1",
                "lab-1",
                Instant.ofEpochMilli(1_000),
                TestStatus.ERROR,
                Duration.ofSeconds(2),
                OptionalInt.empty(),
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
        StoredOutput first = new StoredOutput("a".repeat(40_000), false);
        StoredOutput second = new StoredOutput("b".repeat(40_000), false);

        assertThatThrownBy(() -> new TestRunRecord(
                        "run-1",
                        "lab-1",
                        Instant.EPOCH,
                        TestStatus.ERROR,
                        Duration.ZERO,
                        OptionalInt.empty(),
                        first,
                        second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("combined");
    }

    private static TestRunRecord result(TestStatus status, OptionalInt exitCode) {
        return TestRunRecord.bounded(
                "run-1",
                "lab-1",
                Instant.EPOCH,
                status,
                Duration.ZERO,
                exitCode,
                "",
                "");
    }
}
