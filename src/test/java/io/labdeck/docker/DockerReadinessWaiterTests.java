package io.labdeck.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DockerReadinessWaiterTests {

    @Test
    void returnsOnlyAfterEveryRequiredHealthCheckIsHealthy() {
        AtomicLong time = new AtomicLong();
        AtomicInteger checks = new AtomicInteger();
        DockerReadinessWaiter waiter = waiter(time, ignored -> {});

        List<DockerContainerView> ready = waiter.await(
                List.of(new DockerReadinessWaiter.ServiceProbe("app", true, () ->
                        view("app", checks.incrementAndGet() == 1
                                ? DockerHealthStatus.STARTING : DockerHealthStatus.HEALTHY))),
                Duration.ofSeconds(1),
                CancellationToken.NONE);

        assertThat(ready).singleElement().extracting(DockerContainerView::health)
                .isEqualTo(DockerHealthStatus.HEALTHY);
        assertThat(checks).hasValue(2);
        assertThat(time).hasValue(Duration.ofMillis(100).toNanos());
    }

    @Test
    void reportsTheExactBlockingServicesWhenReadinessTimesOut() {
        AtomicLong time = new AtomicLong();
        DockerReadinessWaiter waiter = waiter(time, ignored -> {});

        assertThatThrownBy(() -> waiter.await(
                List.of(
                        new DockerReadinessWaiter.ServiceProbe(
                                "database", true, () -> view("database", DockerHealthStatus.STARTING)),
                        new DockerReadinessWaiter.ServiceProbe(
                                "app", true, () -> view("app", DockerHealthStatus.HEALTHY))),
                Duration.ofMillis(250),
                CancellationToken.NONE))
                .isInstanceOfSatisfying(DockerServiceReadinessException.class, exception -> {
                    assertThat(exception.reason())
                            .isEqualTo(DockerServiceReadinessException.Reason.TIMED_OUT);
                    assertThat(exception.services()).containsExactly("database");
                });
    }

    @Test
    void aServiceWithoutHealthCriteriaMustStayRunningForTwoSeconds() {
        AtomicLong time = new AtomicLong();
        AtomicInteger checks = new AtomicInteger();
        DockerReadinessWaiter waiter = waiter(time, ignored -> {});

        List<DockerContainerView> ready = waiter.await(
                List.of(new DockerReadinessWaiter.ServiceProbe("app", false, () -> {
                    checks.incrementAndGet();
                    return view("app", DockerHealthStatus.NONE);
                })),
                Duration.ofSeconds(3),
                CancellationToken.NONE);

        assertThat(ready).singleElement().extracting(DockerContainerView::health)
                .isEqualTo(DockerHealthStatus.NONE);
        assertThat(time).hasValue(Duration.ofSeconds(2).toNanos());
        assertThat(checks).hasValue(21);
    }

    @Test
    void distinguishesUnhealthyAndUnexpectedExitFailures() {
        AtomicLong time = new AtomicLong();
        DockerReadinessWaiter waiter = waiter(time, ignored -> {});

        assertThatThrownBy(() -> waiter.await(
                List.of(new DockerReadinessWaiter.ServiceProbe(
                        "database", true, () -> view("database", DockerHealthStatus.UNHEALTHY))),
                Duration.ofSeconds(1),
                CancellationToken.NONE))
                .isInstanceOfSatisfying(DockerServiceReadinessException.class, exception ->
                        assertThat(exception.reason())
                                .isEqualTo(DockerServiceReadinessException.Reason.UNHEALTHY));

        DockerContainerView exited = new DockerContainerView(
                "id", "app", "name", "image", "exited", false,
                OptionalInt.of(17), DockerHealthStatus.NONE, List.of());
        assertThatThrownBy(() -> waiter.await(
                List.of(new DockerReadinessWaiter.ServiceProbe("app", false, () -> exited)),
                Duration.ofSeconds(1),
                CancellationToken.NONE))
                .isInstanceOfSatisfying(DockerServiceReadinessException.class, exception -> {
                    assertThat(exception.reason())
                            .isEqualTo(DockerServiceReadinessException.Reason.EXITED);
                    assertThat(exception.exitCode()).hasValue(17);
                });
    }

    @Test
    void cancellationInterruptsAStartingHealthWait() {
        AtomicLong time = new AtomicLong();
        AtomicBoolean cancelled = new AtomicBoolean();
        DockerReadinessWaiter waiter = waiter(time, ignored -> cancelled.set(true));

        assertThatThrownBy(() -> waiter.await(
                List.of(new DockerReadinessWaiter.ServiceProbe(
                        "app", true, () -> view("app", DockerHealthStatus.STARTING))),
                Duration.ofSeconds(1),
                cancelled::get))
                .isInstanceOf(DockerOperationCancelledException.class);
    }

    private static DockerReadinessWaiter waiter(
            AtomicLong time, java.util.function.Consumer<Duration> afterSleep) {
        return new DockerReadinessWaiter(
                Duration.ofMillis(100),
                time::get,
                duration -> {
                    time.addAndGet(duration.toNanos());
                    afterSleep.accept(duration);
                });
    }

    private static DockerContainerView view(String service, DockerHealthStatus health) {
        return new DockerContainerView(
                "id-" + service,
                service,
                "name-" + service,
                "image",
                "running",
                true,
                OptionalInt.empty(),
                health,
                List.of());
    }
}
