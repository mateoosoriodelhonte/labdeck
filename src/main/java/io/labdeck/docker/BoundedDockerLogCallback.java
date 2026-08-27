package io.labdeck.docker;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class BoundedDockerLogCallback extends ResultCallback.Adapter<Frame>
        implements DockerLogSubscription {

    private final String service;
    private final int maxLines;
    private final int maxBytes;
    private final int maxLineChars;
    private final Consumer<DockerLogLine> consumer;
    private final AtomicInteger lineCount = new AtomicInteger();
    private final AtomicInteger byteCount = new AtomicInteger();
    private final AtomicBoolean truncated = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, ByteArrayOutputStream> pending = new LinkedHashMap<>();
    private final java.util.ArrayList<Runnable> closeListeners = new java.util.ArrayList<>();
    private volatile boolean failed;

    BoundedDockerLogCallback(
            String service,
            int maxLines,
            int maxBytes,
            int maxLineChars,
            Consumer<DockerLogLine> consumer) {
        DockerResourceRecord.requireLogicalName(service);
        if (maxLines < 1 || maxBytes < 1 || maxLineChars < 1) {
            throw new IllegalArgumentException("The Docker log bounds are not valid.");
        }
        this.service = service;
        this.maxLines = maxLines;
        this.maxBytes = maxBytes;
        this.maxLineChars = maxLineChars;
        this.consumer = java.util.Objects.requireNonNull(consumer, "consumer");
    }

    @Override
    public synchronized void onNext(Frame frame) {
        if (closed.get() || frame == null || frame.getPayload() == null) {
            return;
        }
        String outputStream = stream(frame.getStreamType());
        ByteArrayOutputStream line = pending.computeIfAbsent(
                outputStream, ignored -> new ByteArrayOutputStream());
        for (byte value : frame.getPayload()) {
            if (value == '\n') {
                emitLine(line.toByteArray(), outputStream);
                line.reset();
                if (closed.get()) {
                    return;
                }
            } else if (line.size() < (maxLineChars * 4) + 64) {
                line.write(value);
            } else {
                markTruncated();
                emitLine(line.toByteArray(), outputStream);
                close();
                return;
            }
        }
    }

    private void emitLine(byte[] raw, String outputStream) {
        if (closed.get()) {
            return;
        }
        if (lineCount.get() >= maxLines) {
            markTruncated();
            close();
            return;
        }
        ParsedLogLine parsed = parseLogLine(new String(raw, StandardCharsets.UTF_8));
        String text = safeLogText(parsed.text());
        if (text.codePointCount(0, text.length()) > maxLineChars) {
            text = text.substring(0, text.offsetByCodePoints(0, maxLineChars));
            markTruncated();
        }
        int byteRoom = maxBytes - byteCount.get();
        int encodedBytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (encodedBytes > byteRoom) {
            text = fitUtf8(text, byteRoom);
            encodedBytes = text.getBytes(StandardCharsets.UTF_8).length;
            markTruncated();
        }
        if (byteRoom <= 0 && encodedBytes == 0) {
            markTruncated();
            close();
            return;
        }
        try {
            consumer.accept(new DockerLogLine(
                    parsed.timestamp(), service, outputStream, text));
        } catch (RuntimeException failure) {
            failed = true;
            close();
            return;
        }
        lineCount.incrementAndGet();
        byteCount.addAndGet(encodedBytes);
        if (truncated.get()) {
            close();
        }
    }

    private static ParsedLogLine parseLogLine(String value) {
        int delimiter = value.indexOf(' ');
        if (delimiter > 0 && delimiter < 64) {
            try {
                return new ParsedLogLine(
                        Instant.parse(value.substring(0, delimiter)),
                        value.substring(delimiter + 1));
            } catch (RuntimeException ignored) {
                // A malformed daemon timestamp is kept as log text.
            }
        }
        return new ParsedLogLine(Instant.now(), value);
    }

    @Override
    public void onError(Throwable throwable) {
        failed = true;
        close();
    }

    @Override
    public synchronized void onComplete() {
        pending.forEach((outputStream, line) -> {
            if (line.size() > 0 && !closed.get()) {
                emitLine(line.toByteArray(), outputStream);
            }
        });
        close();
    }

    @Override
    public boolean await(Duration timeout) throws InterruptedException {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("The Docker log wait timeout is not valid.");
        }
        return awaitCompletion(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean truncated() {
        return truncated.get();
    }

    @Override
    public boolean failed() {
        return failed;
    }

    @Override
    public boolean closed() {
        return closed.get();
    }

    @Override
    public void onClose(Runnable listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        boolean notifyNow;
        synchronized (this) {
            notifyNow = closed.get();
            if (!notifyNow) {
                closeListeners.add(listener);
            }
        }
        if (notifyNow) {
            notifyListener(listener);
        }
    }

    void markTruncated() {
        truncated.set(true);
    }

    void throwIfFailed() {
        if (failed) {
            throw new DockerLogAccessException();
        }
    }

    @Override
    public void close() {
        List<Runnable> listeners;
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                super.close();
            } catch (IOException ignored) {
                // Closing is best-effort. No Docker transport detail is exposed.
            }
            listeners = List.copyOf(closeListeners);
            closeListeners.clear();
        }
        listeners.forEach(BoundedDockerLogCallback::notifyListener);
    }

    private static void notifyListener(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException ignored) {
            // A local close listener cannot reopen or fail the Docker stream.
        }
    }

    private static String stream(StreamType type) {
        if (type == StreamType.STDOUT) {
            return "STDOUT";
        }
        if (type == StreamType.STDERR) {
            return "STDERR";
        }
        return "CONSOLE";
    }

    private static String safeLogText(String value) {
        StringBuilder safe = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> safe.appendCodePoint(
                (Character.isISOControl(codePoint) && codePoint != '\t')
                                || Character.getType(codePoint) == Character.FORMAT
                        ? 0xfffd : codePoint));
        return safe.toString();
    }

    private static String fitUtf8(String value, int maxBytes) {
        if (maxBytes <= 0) {
            return "";
        }
        int end = value.length();
        while (end > 0
                && value.substring(0, end).getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            end = value.offsetByCodePoints(0, value.codePointCount(0, end) - 1);
        }
        return value.substring(0, end);
    }

    private record ParsedLogLine(Instant timestamp, String text) {}
}
