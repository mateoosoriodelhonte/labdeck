package io.labdeck.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoredOutputTests {

    private static final String CREDENTIAL = "credential-value-91c743";
    private static final Path WORKSPACE = Path.of("/tmp/labdeck-output-workspace");
    private static final TestOutputSanitizer SANITIZER =
            TestOutputSanitizer.forLab(WORKSPACE, List.of(CREDENTIAL));

    @Test
    void keepsOutputAtTheExactUtf8Boundary() {
        String input = "a".repeat(StoredOutput.MAX_UTF8_BYTES);

        StoredOutput output = StoredOutput.bounded(input, StoredOutput.MAX_UTF8_BYTES, SANITIZER);

        assertThat(output.text()).isEqualTo(input);
        assertThat(output.utf8Bytes()).isEqualTo(StoredOutput.MAX_UTF8_BYTES);
        assertThat(output.truncated()).isFalse();
    }

    @Test
    void truncatesWithoutSplittingAMultibyteCharacter() {
        String input = "a".repeat(StoredOutput.MAX_UTF8_BYTES - 2) + "🙂";

        StoredOutput output = StoredOutput.bounded(input, StoredOutput.MAX_UTF8_BYTES, SANITIZER);

        assertThat(output.utf8Bytes()).isEqualTo(StoredOutput.MAX_UTF8_BYTES - 2);
        assertThat(output.text()).doesNotContain("🙂");
        assertThat(output.truncated()).isTrue();
    }

    @Test
    void replacesAnUnpairedSurrogateWithValidUtf8() {
        StoredOutput output = StoredOutput.bounded(
                "before\ud800after", StoredOutput.MAX_UTF8_BYTES, SANITIZER);

        assertThat(output.text()).isEqualTo("before�after");
        assertThat(output.truncated()).isFalse();
    }

    @Test
    void rejectsAnUnboundedStoredValue() {
        assertThatThrownBy(() -> StoredOutput.fromPersistence(
                        "a".repeat(StoredOutput.MAX_UTF8_BYTES + 1), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("byte limit");
    }

    @Test
    void scrubsKnownAndPatternBasedCredentialsBeforePersistence() {
        String raw = "password=hunter2 token:abc123 " + CREDENTIAL + " " + WORKSPACE;

        StoredOutput output = StoredOutput.bounded(raw, StoredOutput.MAX_UTF8_BYTES, SANITIZER);

        assertThat(output.text())
                .contains("password=[REDACTED]", "token:[REDACTED]")
                .doesNotContain("hunter2", "abc123", CREDENTIAL, WORKSPACE.toString());
        assertThat(output.safeToPersist()).isTrue();
        assertThat(output.toString()).doesNotContain(raw, CREDENTIAL, "hunter2");
    }
}
