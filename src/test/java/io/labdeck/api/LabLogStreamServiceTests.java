package io.labdeck.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.labdeck.docker.DockerActiveServiceNotFoundException;
import io.labdeck.docker.DockerLabLifecycle;
import io.labdeck.docker.DockerLogLine;
import io.labdeck.docker.DockerLogSubscription;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class LabLogStreamServiceTests {

    @Test
    void allowsTheDocumentedTwoStreamsForTheSameLabAndService() {
        DockerLabLifecycle lifecycle = mock(DockerLabLifecycle.class);
        DockerLogSubscription first = mock(DockerLogSubscription.class);
        DockerLogSubscription second = mock(DockerLogSubscription.class);
        when(lifecycle.followLogs(eq("lab-1"), eq("app"), eq(20), any()))
                .thenReturn(first, second);
        LabLogStreamService streams = new LabLogStreamService(lifecycle);

        try {
            streams.open("lab-1", "app", 20);
            streams.open("lab-1", "app", 20);

            assertThatThrownBy(() -> streams.open("lab-1", "other", 20))
                    .isInstanceOfSatisfying(ApiException.class, failure ->
                            assertThat(failure.code()).isEqualTo("LOG_STREAM_LIMIT_REACHED"));
        } finally {
            streams.close();
        }
    }

    @Test
    void validatesTheStreamBeforeReturningAnEmitterAndReleasesEverySlot() {
        DockerLabLifecycle lifecycle = mock(DockerLabLifecycle.class);
        when(lifecycle.followLogs(eq("lab-1"), eq("app"), eq(20), any()))
                .thenThrow(new DockerActiveServiceNotFoundException());
        LabLogStreamService streams = new LabLogStreamService(lifecycle);

        try {
            for (int attempt = 0; attempt < 6; attempt++) {
                assertThatThrownBy(() -> streams.open("lab-1", "app", 20))
                        .isInstanceOf(DockerActiveServiceNotFoundException.class);
            }
        } finally {
            streams.close();
        }
    }

    @Test
    void closedSubscriptionsReleaseTwoLabSlotsBeforeTheWorkersExit() {
        DockerLabLifecycle lifecycle = mock(DockerLabLifecycle.class);
        List<ControlledSubscription> subscriptions = new ArrayList<>();
        when(lifecycle.followLogs(eq("lab-1"), eq("app"), eq(20), any()))
                .thenAnswer(ignored -> {
                    ControlledSubscription subscription = new ControlledSubscription();
                    subscriptions.add(subscription);
                    return subscription;
                });
        LabLogStreamService streams = new LabLogStreamService(lifecycle);

        try {
            streams.open("lab-1", "app", 20);
            streams.open("lab-1", "app", 20);
            subscriptions.get(0).close();
            subscriptions.get(1).close();

            streams.open("lab-1", "app", 20);
            streams.open("lab-1", "app", 20);
            assertThatThrownBy(() -> streams.open("lab-1", "app", 20))
                    .isInstanceOfSatisfying(ApiException.class, failure ->
                            assertThat(failure.code()).isEqualTo("LOG_STREAM_LIMIT_REACHED"));
        } finally {
            streams.close();
        }
    }

    @Test
    void limitsTheWholeProcessToFourStreams() {
        DockerLabLifecycle lifecycle = mock(DockerLabLifecycle.class);
        when(lifecycle.followLogs(anyString(), eq("app"), anyInt(), any()))
                .thenAnswer(ignored -> new ControlledSubscription());
        LabLogStreamService streams = new LabLogStreamService(lifecycle);

        try {
            streams.open("lab-1", "app", 20);
            streams.open("lab-1", "app", 20);
            streams.open("lab-2", "app", 20);
            streams.open("lab-2", "app", 20);

            assertThatThrownBy(() -> streams.open("lab-3", "app", 20))
                    .isInstanceOfSatisfying(ApiException.class, failure ->
                            assertThat(failure.code()).isEqualTo("LOG_STREAM_LIMIT_REACHED"));
        } finally {
            streams.close();
        }
    }

    @Test
    void concurrentCloseAndOpenCannotSplitThePerLabCounter() throws Exception {
        try (ExecutorService attempts = Executors.newFixedThreadPool(9)) {
            for (int round = 0; round < 50; round++) {
                DockerLabLifecycle lifecycle = mock(DockerLabLifecycle.class);
                List<ControlledSubscription> subscriptions = new CopyOnWriteArrayList<>();
                when(lifecycle.followLogs(eq("lab-1"), eq("app"), eq(20), any()))
                        .thenAnswer(ignored -> {
                            ControlledSubscription subscription = new ControlledSubscription();
                            subscriptions.add(subscription);
                            return subscription;
                        });
                LabLogStreamService streams = new LabLogStreamService(lifecycle);
                streams.open("lab-1", "app", 20);
                ControlledSubscription existing = subscriptions.getFirst();
                CountDownLatch start = new CountDownLatch(1);
                Future<?> close = attempts.submit(() -> {
                    await(start);
                    existing.close();
                });
                List<Future<Boolean>> opens = new ArrayList<>();
                for (int attempt = 0; attempt < 8; attempt++) {
                    opens.add(attempts.submit(() -> {
                        await(start);
                        try {
                            streams.open("lab-1", "app", 20);
                            return true;
                        } catch (ApiException failure) {
                            assertThat(failure.code()).isEqualTo("LOG_STREAM_LIMIT_REACHED");
                            return false;
                        }
                    }));
                }

                try {
                    start.countDown();
                    close.get(1, TimeUnit.SECONDS);
                    int opened = 0;
                    for (Future<Boolean> result : opens) {
                        if (result.get(1, TimeUnit.SECONDS)) {
                            opened++;
                        }
                    }
                    assertThat(opened).isLessThanOrEqualTo(2);
                } finally {
                    subscriptions.forEach(ControlledSubscription::close);
                    streams.close();
                }
            }
        }
    }

    @Test
    void queueOverflowIsBoundedAndEndsWithAStableReason() throws Exception {
        DockerLabLifecycle lifecycle = mock(DockerLabLifecycle.class);
        AtomicReference<java.util.function.Consumer<DockerLogLine>> consumer = new AtomicReference<>();
        ControlledSubscription subscription = new ControlledSubscription();
        when(lifecycle.followLogs(anyString(), eq("app"), eq(20), any()))
                .thenAnswer(invocation -> {
                    if ("lab-1".equals(invocation.getArgument(0))) {
                        consumer.set(invocation.getArgument(3));
                        return subscription;
                    }
                    return new ControlledSubscription();
                });
        BlockingEmitter emitter = new BlockingEmitter();
        AtomicBoolean firstEmitter = new AtomicBoolean(true);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        LabLogStreamService streams = new LabLogStreamService(
                lifecycle,
                executor,
                timeout -> firstEmitter.compareAndSet(true, false)
                        ? emitter : new SseEmitter(timeout));

        try {
            streams.open("lab-1", "app", 20);
            consumer.get().accept(line("first"));
            assertThat(emitter.awaitFirstSend()).isTrue();
            for (int index = 0; index < 128; index++) {
                consumer.get().accept(line("queued-" + index));
            }

            assertThatThrownBy(() -> consumer.get().accept(line("overflow")))
                    .isInstanceOf(RuntimeException.class);
            subscription.close();

            streams.open("lab-2", "app", 20);
            streams.open("lab-2", "app", 20);
            streams.open("lab-3", "app", 20);
            assertThatThrownBy(() -> streams.open("lab-3", "app", 20))
                    .isInstanceOfSatisfying(ApiException.class, failure ->
                            assertThat(failure.code()).isEqualTo("LOG_STREAM_LIMIT_REACHED"));
            emitter.releaseFirstSend();

            assertThat(emitter.awaitEnd()).isTrue();
            assertThat(emitter.data()).contains(Map.of("reason", "OVERFLOW"));
        } finally {
            emitter.releaseFirstSend();
            streams.close();
        }
    }

    @Test
    void executorRejectionReleasesEveryStreamSlot() {
        LabLogStreamService streams = new LabLogStreamService(mock(DockerLabLifecycle.class));
        streams.close();

        for (int attempt = 0; attempt < 6; attempt++) {
            assertThatThrownBy(() -> streams.open("lab-1", "app", 20))
                    .isInstanceOfSatisfying(ApiException.class, failure ->
                            assertThat(failure.code()).isEqualTo("LOG_STREAM_UNAVAILABLE"));
        }
    }

    private static final class ControlledSubscription implements DockerLogSubscription {
        private final AtomicBoolean closed = new AtomicBoolean();
        private Runnable closeListener = () -> {};

        @Override
        public boolean await(Duration timeout) {
            return closed.get();
        }

        @Override
        public boolean truncated() {
            return false;
        }

        @Override
        public boolean failed() {
            return false;
        }

        @Override
        public boolean closed() {
            return closed.get();
        }

        @Override
        public void onClose(Runnable listener) {
            boolean notifyNow;
            synchronized (this) {
                notifyNow = closed.get();
                if (!notifyNow) {
                    closeListener = listener;
                }
            }
            if (notifyNow) {
                listener.run();
            }
        }

        @Override
        public void close() {
            Runnable listener;
            synchronized (this) {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                listener = closeListener;
            }
            listener.run();
        }
    }

    private static DockerLogLine line(String text) {
        return new DockerLogLine(
                Instant.parse("2026-08-27T12:00:00Z"), "app", "STDOUT", text);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The quota test was interrupted.", exception);
        }
    }

    private static final class BlockingEmitter extends SseEmitter {
        private final AtomicBoolean first = new AtomicBoolean(true);
        private final CountDownLatch firstSend = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch end = new CountDownLatch(1);
        private final List<Object> data = new CopyOnWriteArrayList<>();

        private BlockingEmitter() {
            super(300_000L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (first.compareAndSet(true, false)) {
                firstSend.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Test emitter was interrupted.", exception);
                }
            } else {
                end.countDown();
            }
            builder.build().forEach(item -> data.add(item.getData()));
        }

        private boolean awaitFirstSend() throws InterruptedException {
            return firstSend.await(1, TimeUnit.SECONDS);
        }

        private void releaseFirstSend() {
            releaseFirst.countDown();
        }

        private boolean awaitEnd() throws InterruptedException {
            return end.await(1, TimeUnit.SECONDS);
        }

        private List<Object> data() {
            return List.copyOf(data);
        }
    }
}
