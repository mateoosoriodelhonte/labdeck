package io.labdeck.docker;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BoundedDockerLogCallbackTests {

    @Test
    void sanitizesControlsAndStopsAtTheCodePointLimit() {
        List<DockerLogLine> lines = new ArrayList<>();
        BoundedDockerLogCallback callback = new BoundedDockerLogCallback(
                "app", 5, 1_024, 5, lines::add);

        callback.onNext(frame(StreamType.STDOUT,
                "2026-08-27T12:00:00Z ab\u001b\u202ecdZZ\n"));

        assertThat(lines).containsExactly(new DockerLogLine(
                Instant.parse("2026-08-27T12:00:00Z"),
                "app",
                "STDOUT",
                "ab��c"));
        assertThat(callback.truncated()).isTrue();
        assertThat(callback.closed()).isTrue();
    }

    @Test
    void appliesTheUtf8ByteLimitWithoutSplittingACharacter() {
        List<DockerLogLine> lines = new ArrayList<>();
        BoundedDockerLogCallback callback = new BoundedDockerLogCallback(
                "app", 5, 5, 100, lines::add);

        callback.onNext(frame(StreamType.STDOUT,
                "2026-08-27T12:00:00Z ééé\n"));

        assertThat(lines).singleElement().satisfies(line -> assertThat(line.text()).isEqualTo("éé"));
        assertThat(lines.getFirst().text().getBytes(StandardCharsets.UTF_8)).hasSize(4);
        assertThat(callback.truncated()).isTrue();
    }

    @Test
    void keepsInterleavedStdoutAndStderrFragmentsSeparate() {
        List<DockerLogLine> lines = new ArrayList<>();
        BoundedDockerLogCallback callback = new BoundedDockerLogCallback(
                "app", 5, 1_024, 100, lines::add);

        callback.onNext(frame(StreamType.STDOUT, "2026-08-27T12:00:00Z out"));
        callback.onNext(frame(StreamType.STDERR, "2026-08-27T12:00:01Z error\n"));
        callback.onNext(frame(StreamType.STDOUT, "put\n"));
        callback.onComplete();

        assertThat(lines).containsExactly(
                new DockerLogLine(
                        Instant.parse("2026-08-27T12:00:01Z"), "app", "STDERR", "error"),
                new DockerLogLine(
                        Instant.parse("2026-08-27T12:00:00Z"), "app", "STDOUT", "output"));
        assertThat(callback.truncated()).isFalse();
        assertThat(callback.closed()).isTrue();
    }

    @Test
    void reportsTransportFailureAndNotifiesTheCloseListener() {
        BoundedDockerLogCallback callback = new BoundedDockerLogCallback(
                "app", 5, 1_024, 100, ignored -> {});
        AtomicBoolean notified = new AtomicBoolean();
        callback.onClose(() -> notified.set(true));

        callback.onError(new IllegalStateException("private transport detail"));

        assertThat(callback.failed()).isTrue();
        assertThat(callback.closed()).isTrue();
        assertThat(notified).isTrue();
    }

    private static Frame frame(StreamType stream, String text) {
        return new Frame(stream, text.getBytes(StandardCharsets.UTF_8));
    }
}
