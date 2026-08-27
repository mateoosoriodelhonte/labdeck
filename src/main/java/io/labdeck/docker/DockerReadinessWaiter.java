package io.labdeck.docker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class DockerReadinessWaiter {

    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(100);
    private static final Duration RUNNING_STABILITY = Duration.ofSeconds(2);
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(15);

    private final Duration pollInterval;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;

    DockerReadinessWaiter() {
        this(DEFAULT_POLL_INTERVAL, System::nanoTime, duration -> Thread.sleep(duration.toMillis()));
    }

    DockerReadinessWaiter(Duration pollInterval, LongSupplier nanoTime, Sleeper sleeper) {
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()
                || pollInterval.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("The readiness poll interval is not valid.");
        }
        this.pollInterval = pollInterval;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    List<DockerContainerView> await(
            List<ServiceProbe> probes, Duration timeout, CancellationToken cancellation) {
        probes = List.copyOf(probes);
        if (probes.isEmpty()) {
            throw new IllegalArgumentException("At least one service readiness probe is required.");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("The readiness timeout is not valid.");
        }
        cancellation = cancellation == null ? CancellationToken.NONE : cancellation;
        long timeoutNanos = timeout.toNanos();
        long startedAt = nanoTime.getAsLong();
        Map<String, Long> firstRunningAt = new HashMap<>();

        while (true) {
            cancellation.throwIfCancellationRequested();
            List<DockerContainerView> views = new ArrayList<>(probes.size());
            List<String> blocking = new ArrayList<>();
            for (ServiceProbe probe : probes) {
                cancellation.throwIfCancellationRequested();
                DockerContainerView view = Objects.requireNonNull(probe.inspection().get(), "inspection");
                if (!probe.service().equals(view.service())) {
                    throw new IllegalStateException("Docker returned readiness for the wrong service.");
                }
                if (!view.running()) {
                    throw DockerServiceReadinessException.exited(probe.service(), view.exitCode());
                }
                if (probe.healthRequired()) {
                    switch (view.health()) {
                        case HEALTHY -> {
                            // Ready.
                        }
                        case STARTING -> blocking.add(probe.service());
                        case UNHEALTHY -> throw DockerServiceReadinessException.unhealthy(probe.service());
                        case NONE, UNKNOWN -> throw DockerServiceReadinessException.healthNotReported(
                                probe.service());
                    }
                } else {
                    long observedAt = nanoTime.getAsLong();
                    long stableSince = firstRunningAt.computeIfAbsent(probe.service(), ignored -> observedAt);
                    long stableFor = observedAt - stableSince;
                    if (stableFor < 0 || stableFor < RUNNING_STABILITY.toNanos()) {
                        blocking.add(probe.service());
                    }
                }
                views.add(view);
            }
            if (blocking.isEmpty()) {
                return List.copyOf(views);
            }

            long elapsed = nanoTime.getAsLong() - startedAt;
            if (elapsed < 0 || elapsed >= timeoutNanos) {
                throw DockerServiceReadinessException.timedOut(blocking);
            }
            Duration remaining = Duration.ofNanos(timeoutNanos - elapsed);
            Duration delay = remaining.compareTo(pollInterval) < 0 ? remaining : pollInterval;
            try {
                sleeper.sleep(delay);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Docker readiness waiting was interrupted.", exception);
            }
        }
    }

    record ServiceProbe(String service, boolean healthRequired, Supplier<DockerContainerView> inspection) {
        ServiceProbe {
            if (service == null || service.isBlank()) {
                throw new IllegalArgumentException("The service readiness name is required.");
            }
            Objects.requireNonNull(inspection, "inspection");
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
