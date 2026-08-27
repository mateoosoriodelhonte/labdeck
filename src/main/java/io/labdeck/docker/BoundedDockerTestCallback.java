package io.labdeck.docker;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class BoundedDockerTestCallback extends ResultCallback.Adapter<Frame> {

    static final int MAX_CAPTURE_BYTES = 128 * 1024;

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final CountDownLatch completed = new CountDownLatch(1);
    private int capturedBytes;
    private boolean stdoutTruncated;
    private boolean stderrTruncated;
    private boolean failed;

    @Override
    public synchronized void onNext(Frame frame) {
        if (frame == null || frame.getPayload() == null || frame.getPayload().length == 0) {
            return;
        }
        ByteArrayOutputStream target = frame.getStreamType() == StreamType.STDERR ? stderr : stdout;
        int remaining = MAX_CAPTURE_BYTES - capturedBytes;
        int accepted = Math.min(remaining, frame.getPayload().length);
        if (accepted > 0) {
            target.write(frame.getPayload(), 0, accepted);
            capturedBytes += accepted;
        }
        if (accepted < frame.getPayload().length) {
            markTruncated(frame.getStreamType());
        }
    }

    @Override
    public synchronized void onError(Throwable throwable) {
        failed = true;
        try {
            close();
        } finally {
            completed.countDown();
        }
    }

    @Override
    public void onComplete() {
        try {
            super.onComplete();
        } finally {
            completed.countDown();
        }
    }

    synchronized boolean failed() {
        return failed;
    }

    synchronized String stdout() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    synchronized String stderr() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    synchronized boolean stdoutTruncated() {
        return stdoutTruncated;
    }

    synchronized boolean stderrTruncated() {
        return stderrTruncated;
    }

    boolean await(Duration timeout) throws InterruptedException {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("The Docker test wait timeout is not valid.");
        }
        return completed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        try {
            super.close();
        } catch (IOException ignored) {
            // Closing is best-effort. No Docker transport detail is exposed.
        }
    }

    private void markTruncated(StreamType streamType) {
        if (streamType == StreamType.STDERR) {
            stderrTruncated = true;
        } else {
            stdoutTruncated = true;
        }
    }
}
