package io.labdeck.docker;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BoundedDockerTestCallbackTests {

    @Test
    void joinsSplitUtf8FramesAndKeepsStreamsSeparate() {
        BoundedDockerTestCallback callback = new BoundedDockerTestCallback();
        byte[] emoji = "🙂".getBytes(StandardCharsets.UTF_8);

        callback.onNext(new Frame(StreamType.STDOUT, new byte[] {emoji[0], emoji[1]}));
        callback.onNext(new Frame(StreamType.STDERR, "problem".getBytes(StandardCharsets.UTF_8)));
        callback.onNext(new Frame(StreamType.STDOUT, new byte[] {emoji[2], emoji[3]}));

        assertThat(callback.stdout()).isEqualTo("🙂");
        assertThat(callback.stderr()).isEqualTo("problem");
        assertThat(callback.stdoutTruncated()).isFalse();
        assertThat(callback.stderrTruncated()).isFalse();
    }

    @Test
    void boundsCombinedCaptureAndContinuesAcceptingDiscardedFrames() {
        BoundedDockerTestCallback callback = new BoundedDockerTestCallback();

        callback.onNext(new Frame(
                StreamType.STDOUT,
                "a".repeat(BoundedDockerTestCallback.MAX_CAPTURE_BYTES).getBytes(StandardCharsets.UTF_8)));
        callback.onNext(new Frame(StreamType.STDERR, "discarded".getBytes(StandardCharsets.UTF_8)));
        callback.onComplete();

        assertThat(callback.stdout().getBytes(StandardCharsets.UTF_8))
                .hasSize(BoundedDockerTestCallback.MAX_CAPTURE_BYTES);
        assertThat(callback.stderr()).isEmpty();
        assertThat(callback.stderrTruncated()).isTrue();
    }

    @Test
    void recordsTransportFailureWithoutKeepingItsMessage() {
        BoundedDockerTestCallback callback = new BoundedDockerTestCallback();

        callback.onError(new IllegalStateException("secret daemon path"));

        assertThat(callback.failed()).isTrue();
        assertThat(callback.toString()).doesNotContain("secret daemon path");
    }

    @Test
    void aTimedWaitDoesNotCloseTheActiveDockerStream() throws Exception {
        BoundedDockerTestCallback callback = new BoundedDockerTestCallback();
        AtomicBoolean closed = new AtomicBoolean();
        callback.onStart(() -> closed.set(true));

        assertThat(callback.await(Duration.ofMillis(10))).isFalse();
        assertThat(closed).isFalse();

        callback.onComplete();
        assertThat(callback.await(Duration.ofMillis(10))).isTrue();
        assertThat(closed).isTrue();
    }
}
