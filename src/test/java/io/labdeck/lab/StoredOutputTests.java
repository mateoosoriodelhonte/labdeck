package io.labdeck.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StoredOutputTests {

    @Test
    void keepsOutputAtTheExactUtf8Boundary() {
        String input = "a".repeat(StoredOutput.MAX_UTF8_BYTES);

        StoredOutput output = StoredOutput.bounded(input);

        assertThat(output.text()).isEqualTo(input);
        assertThat(output.utf8Bytes()).isEqualTo(StoredOutput.MAX_UTF8_BYTES);
        assertThat(output.truncated()).isFalse();
    }

    @Test
    void truncatesWithoutSplittingAMultibyteCharacter() {
        String input = "a".repeat(StoredOutput.MAX_UTF8_BYTES - 2) + "🙂";

        StoredOutput output = StoredOutput.bounded(input);

        assertThat(output.utf8Bytes()).isEqualTo(StoredOutput.MAX_UTF8_BYTES - 2);
        assertThat(output.text()).doesNotContain("🙂");
        assertThat(output.truncated()).isTrue();
    }

    @Test
    void replacesAnUnpairedSurrogateWithValidUtf8() {
        StoredOutput output = StoredOutput.bounded("before\ud800after");

        assertThat(output.text()).isEqualTo("before�after");
        assertThat(output.truncated()).isFalse();
    }

    @Test
    void rejectsAnUnboundedStoredValue() {
        assertThatThrownBy(() -> new StoredOutput("a".repeat(StoredOutput.MAX_UTF8_BYTES + 1), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("byte limit");
    }
}
