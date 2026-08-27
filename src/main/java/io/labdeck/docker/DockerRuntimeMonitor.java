package io.labdeck.docker;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class DockerRuntimeMonitor implements DockerRuntimeMonitorPort {

    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(2);
    private static final int MAX_INSPECTION_FAILURES = 3;

    private final Duration interval;
    private final Sleeper sleeper;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, Watch> watches = new ConcurrentHashMap<>();

    DockerRuntimeMonitor() {
        this(DEFAULT_INTERVAL, duration -> Thread.sleep(duration.toMillis()),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    DockerRuntimeMonitor(Duration interval, Sleeper sleeper, ExecutorService executor) {
        if (interval == null || interval.isZero() || interval.isNegative()
                || interval.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("The runtime monitor interval is not valid.");
        }
        this.interval = interval;
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public void watch(
            String labId,
            long runningRevision,
            List<DockerReadinessWaiter.ServiceProbe> probes,
            Consumer<RuntimeFailure> failureHandler) {
        if (labId == null || labId.isBlank() || runningRevision < 0) {
            throw new IllegalArgumentException("The monitored lab identity is not valid.");
        }
        probes = List.copyOf(probes);
        if (probes.isEmpty()) {
            throw new IllegalArgumentException("At least one runtime probe is required.");
        }
        Objects.requireNonNull(failureHandler, "failureHandler");

        Watch watch = new Watch(runningRevision);
        Watch previous = watches.put(labId, watch);
        if (previous != null) {
            previous.cancel();
        }
        List<DockerReadinessWaiter.ServiceProbe> monitoredProbes = probes;
        Future<?> future = executor.submit(() -> monitor(
                labId, watch, monitoredProbes, failureHandler));
        watch.attach(future);
    }

    @Override
    public void cancel(String labId) {
        Watch watch = watches.remove(labId);
        if (watch != null) {
            watch.cancel();
        }
    }

    @Override
    public void close() {
        watches.values().forEach(Watch::cancel);
        watches.clear();
        executor.close();
    }

    private void monitor(
            String labId,
            Watch watch,
            List<DockerReadinessWaiter.ServiceProbe> probes,
            Consumer<RuntimeFailure> failureHandler) {
        int inspectionFailures = 0;
        try {
            while (!watch.cancelled()) {
                sleeper.sleep(interval);
                if (watch.cancelled()) {
                    return;
                }
                try {
                    RuntimeFailure serviceFailure = inspect(probes);
                    inspectionFailures = 0;
                    if (serviceFailure != null) {
                        failureHandler.accept(serviceFailure);
                        return;
                    }
                } catch (DockerOwnershipException ownershipMismatch) {
                    failureHandler.accept(RuntimeFailure.ownershipChanged());
                    return;
                } catch (RuntimeException inspectionFailure) {
                    inspectionFailures++;
                    if (inspectionFailures >= MAX_INSPECTION_FAILURES) {
                        failureHandler.accept(RuntimeFailure.inspectionFailed());
                        return;
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            watches.remove(labId, watch);
        }
    }

    private static RuntimeFailure inspect(List<DockerReadinessWaiter.ServiceProbe> probes) {
        for (DockerReadinessWaiter.ServiceProbe probe : probes) {
            DockerContainerView view = Objects.requireNonNull(probe.inspection().get(), "inspection");
            if (!probe.service().equals(view.service())) {
                throw new IllegalStateException("Docker returned runtime state for the wrong service.");
            }
            if (!view.running()) {
                return RuntimeFailure.serviceFailed(
                        DockerServiceReadinessException.exited(view.service(), view.exitCode()));
            }
            if (probe.healthRequired()) {
                if (view.health() == DockerHealthStatus.UNHEALTHY) {
                    return RuntimeFailure.serviceFailed(
                            DockerServiceReadinessException.unhealthy(view.service()));
                }
                if (view.health() != DockerHealthStatus.HEALTHY) {
                    return RuntimeFailure.serviceFailed(
                            DockerServiceReadinessException.healthNotReported(view.service()));
                }
            }
        }
        return null;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final class Watch {
        private final long runningRevision;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Future<?>> future = new AtomicReference<>();

        private Watch(long runningRevision) {
            this.runningRevision = runningRevision;
        }

        private void attach(Future<?> value) {
            if (!future.compareAndSet(null, value)) {
                value.cancel(true);
                throw new IllegalStateException("The runtime monitor was already attached.");
            }
            if (cancelled()) {
                value.cancel(true);
            }
        }

        private boolean cancelled() {
            return cancelled.get();
        }

        private void cancel() {
            cancelled.set(true);
            Future<?> active = future.get();
            if (active != null) {
                active.cancel(true);
            }
        }

        @Override
        public String toString() {
            return "Watch[runningRevision=" + runningRevision + ", cancelled=" + cancelled() + "]";
        }
    }
}
