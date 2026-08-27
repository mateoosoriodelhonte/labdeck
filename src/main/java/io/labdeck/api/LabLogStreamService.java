package io.labdeck.api;

import io.labdeck.api.LabApiModels.LogLineResponse;
import io.labdeck.docker.DockerLogLine;
import io.labdeck.docker.DockerLogSubscription;
import io.labdeck.docker.DockerLabLifecycle;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class LabLogStreamService implements AutoCloseable {

    private static final Duration STREAM_LIFETIME = Duration.ofMinutes(5);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final int MAX_PROCESS_STREAMS = 4;
    private static final int MAX_LAB_STREAMS = 2;
    private static final int MAX_QUEUE_EVENTS = 128;
    private static final long MAX_QUEUE_BYTES = 256L * 1_024;
    private static final QueuedLog CLOSED_SIGNAL = new QueuedLog(null, 0);

    private final DockerLabLifecycle lifecycle;
    private final ExecutorService executor;
    private final LongFunction<SseEmitter> emitterFactory;
    private final Semaphore processSlots = new Semaphore(MAX_PROCESS_STREAMS);
    private final Map<String, Integer> labCounts = new ConcurrentHashMap<>();
    private final Map<StreamKey, StreamState> streams = new ConcurrentHashMap<>();
    private final AtomicLong streamIds = new AtomicLong();

    @Autowired
    public LabLogStreamService(DockerLabLifecycle lifecycle) {
        this(lifecycle, Executors.newVirtualThreadPerTaskExecutor(), SseEmitter::new);
    }

    LabLogStreamService(
            DockerLabLifecycle lifecycle,
            ExecutorService executor,
            LongFunction<SseEmitter> emitterFactory) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.emitterFactory = Objects.requireNonNull(emitterFactory, "emitterFactory");
    }

    public SseEmitter open(String labId, String service, int tail) {
        StreamKey key = new StreamKey(labId, service, streamIds.incrementAndGet());
        if (!processSlots.tryAcquire()) {
            throw tooManyStreams();
        }
        if (!tryAcquireLabSlot(labId)) {
            processSlots.release();
            throw tooManyStreams();
        }

        SseEmitter emitter;
        try {
            emitter = Objects.requireNonNull(
                    emitterFactory.apply(STREAM_LIFETIME.toMillis()), "emitter");
        } catch (RuntimeException failure) {
            releaseLabSlot(labId);
            processSlots.release();
            throw failure;
        }
        StreamState state = new StreamState(key, emitter);
        streams.put(key, state);
        emitter.onCompletion(state::cancel);
        emitter.onTimeout(state::cancel);
        emitter.onError(ignored -> state.cancel());
        try {
            if (executor.isShutdown()) {
                throw streamUnavailable();
            }
            DockerLogSubscription subscription = lifecycle.followLogs(
                    state.key.labId(), state.key.service(), tail, state::enqueue);
            state.subscription.set(subscription);
            subscription.onClose(state::subscriptionClosed);
            if (state.cancelled.get()) {
                subscription.close();
                throw streamUnavailable();
            }
            state.workerScheduled.set(true);
            executor.submit(() -> run(state));
        } catch (RejectedExecutionException failure) {
            state.workerScheduled.set(false);
            state.cancel();
            throw streamUnavailable();
        } catch (RuntimeException failure) {
            state.cancel();
            throw failure;
        }
        return emitter;
    }

    private void run(StreamState state) {
        Instant deadline = Instant.now().plus(STREAM_LIFETIME);
        String reason = "COMPLETE";
        DockerLogSubscription subscription = state.subscription.get();
        try {
            while (!state.cancelled.get()) {
                if (subscription.closed() && state.queue.isEmpty()) {
                    reason = terminalReason(state, subscription);
                    break;
                }
                QueuedLog next = state.queue.poll(
                        HEARTBEAT_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
                if (next != null && next.line() != null) {
                    state.queueBytes.addAndGet(-next.bytes());
                    state.emitter.send(SseEmitter.event()
                            .name("log")
                            .data(new LogLineResponse(
                                    next.line().timestamp(),
                                    next.line().service(),
                                    next.line().stream(),
                                    next.line().text())));
                } else if (!subscription.closed()) {
                    state.emitter.send(SseEmitter.event().comment("keepalive"));
                }
                if (state.overflow.get()) {
                    reason = "OVERFLOW";
                    break;
                }
                if (subscription.closed() && state.queue.isEmpty()) {
                    reason = terminalReason(state, subscription);
                    break;
                }
                if (!Instant.now().isBefore(deadline)) {
                    reason = "TIMEOUT";
                    break;
                }
            }
            subscription.close();
            if (!state.cancelled.get()) {
                state.emitter.send(SseEmitter.event()
                        .name("end")
                        .data(Map.of("reason", reason)));
                state.emitter.complete();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            if (!state.cancelled.get()) {
                try {
                    state.emitter.send(SseEmitter.event()
                            .name("end")
                            .data(Map.of("reason", "ERROR")));
                } catch (IOException ignored) {
                    // The client is already gone.
                }
                state.emitter.complete();
            }
        } finally {
            state.cancel();
            state.releaseProcessSlot();
        }
    }

    private static String terminalReason(
            StreamState state, DockerLogSubscription subscription) {
        if (state.overflow.get()) {
            return "OVERFLOW";
        }
        if (subscription.failed()) {
            return "ERROR";
        }
        return subscription.truncated() ? "LIMIT" : "COMPLETE";
    }

    @Override
    @PreDestroy
    public void close() {
        streams.values().forEach(StreamState::cancel);
        executor.close();
    }

    private static ApiException tooManyStreams() {
        return new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "LOG_STREAM_LIMIT_REACHED",
                "Log stream limit reached",
                "Close another log stream, then retry.");
    }

    private static ApiException streamUnavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "LOG_STREAM_UNAVAILABLE",
                "Log stream unavailable",
                "The log stream worker is not available. Retry later.");
    }

    private boolean tryAcquireLabSlot(String labId) {
        AtomicBoolean acquired = new AtomicBoolean();
        labCounts.compute(labId, (ignored, current) -> {
            int active = current == null ? 0 : current;
            if (active >= MAX_LAB_STREAMS) {
                return current;
            }
            acquired.set(true);
            return active + 1;
        });
        return acquired.get();
    }

    private void releaseLabSlot(String labId) {
        labCounts.computeIfPresent(labId, (ignored, current) -> current == 1 ? null : current - 1);
    }

    private record StreamKey(String labId, String service, long id) {}

    private record QueuedLog(DockerLogLine line, int bytes) {}

    private final class StreamState {
        private final StreamKey key;
        private final SseEmitter emitter;
        private final ArrayBlockingQueue<QueuedLog> queue = new ArrayBlockingQueue<>(MAX_QUEUE_EVENTS);
        private final AtomicLong queueBytes = new AtomicLong();
        private final AtomicBoolean overflow = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean labSlotReleased = new AtomicBoolean();
        private final AtomicBoolean processSlotReleased = new AtomicBoolean();
        private final AtomicBoolean workerScheduled = new AtomicBoolean();
        private final AtomicReference<DockerLogSubscription> subscription = new AtomicReference<>();

        private StreamState(StreamKey key, SseEmitter emitter) {
            this.key = key;
            this.emitter = emitter;
        }

        private void enqueue(DockerLogLine line) {
            int bytes = line.text().getBytes(StandardCharsets.UTF_8).length;
            long queued = queueBytes.addAndGet(bytes);
            QueuedLog item = new QueuedLog(line, bytes);
            if (queued > MAX_QUEUE_BYTES || !queue.offer(item)) {
                queueBytes.addAndGet(-bytes);
                overflow.set(true);
                throw new StreamLimitReachedException();
            }
        }

        private void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            DockerLogSubscription active = subscription.get();
            if (active != null) {
                active.close();
            }
            queue.offer(CLOSED_SIGNAL);
            releaseLabSlot();
            if (!workerScheduled.get()) {
                releaseProcessSlot();
            }
        }

        private void subscriptionClosed() {
            queue.offer(CLOSED_SIGNAL);
            releaseLabSlot();
        }

        private void releaseLabSlot() {
            if (!labSlotReleased.compareAndSet(false, true)) {
                return;
            }
            LabLogStreamService.this.releaseLabSlot(key.labId());
        }

        private void releaseProcessSlot() {
            if (!processSlotReleased.compareAndSet(false, true)) {
                return;
            }
            streams.remove(key, this);
            processSlots.release();
        }
    }

    private static final class StreamLimitReachedException extends RuntimeException {}
}
