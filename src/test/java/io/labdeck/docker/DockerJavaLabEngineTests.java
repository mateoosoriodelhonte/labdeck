package io.labdeck.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.transport.DockerHttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DockerJavaLabEngineTests {

    @Test
    void joinsSplitFramesAndUsesTheDockerTimestamp() {
        DockerClient docker = mock(DockerClient.class);
        DockerHttpClient http = mock(DockerHttpClient.class);
        DockerResourceRecord container = activeContainer();
        mockOwnedInspection(docker, container);
        LogContainerCmd logs = mock(LogContainerCmd.class);
        when(docker.logContainerCmd("container-id")).thenReturn(logs);
        when(logs.withStdOut(anyBoolean())).thenReturn(logs);
        when(logs.withStdErr(anyBoolean())).thenReturn(logs);
        when(logs.withTimestamps(anyBoolean())).thenReturn(logs);
        when(logs.withTail(anyInt())).thenReturn(logs);
        when(logs.withSince(anyInt())).thenReturn(logs);
        when(logs.withUntil(anyInt())).thenReturn(logs);
        when(logs.withFollowStream(anyBoolean())).thenReturn(logs);
        doAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    ResultCallback<Frame> callback = invocation.getArgument(0);
                    callback.onNext(new Frame(
                            StreamType.STDOUT,
                            "2026-08-27T12:34:56.123456789Z hel".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    callback.onNext(new Frame(
                            StreamType.STDOUT,
                            "lo\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    callback.onComplete();
                    return callback;
                })
                .when(logs).exec(any());

        DockerLogBatch batch = new DockerJavaLabEngine(docker, http)
                .readContainerLogs(container, 20);

        assertThat(batch.truncated()).isFalse();
        assertThat(batch.lines()).containsExactly(new DockerLogLine(
                Instant.parse("2026-08-27T12:34:56.123456789Z"),
                "app",
                "STDOUT",
                "hello"));
    }

    @Test
    void closeWaitsForAnInFlightLogFrameBeforeReturning() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerHttpClient http = mock(DockerHttpClient.class);
        DockerResourceRecord container = activeContainer();
        mockOwnedInspection(docker, container);
        LogContainerCmd logs = mock(LogContainerCmd.class);
        when(docker.logContainerCmd("container-id")).thenReturn(logs);
        when(logs.withStdOut(anyBoolean())).thenReturn(logs);
        when(logs.withStdErr(anyBoolean())).thenReturn(logs);
        when(logs.withTimestamps(anyBoolean())).thenReturn(logs);
        when(logs.withTail(anyInt())).thenReturn(logs);
        when(logs.withSince(anyInt())).thenReturn(logs);
        when(logs.withUntil(anyInt())).thenReturn(logs);
        when(logs.withFollowStream(anyBoolean())).thenReturn(logs);
        AtomicReference<ResultCallback<Frame>> callback = new AtomicReference<>();
        doAnswer(invocation -> {
                    callback.set(invocation.getArgument(0));
                    return callback.get();
                })
                .when(logs).exec(any());
        CountDownLatch consumerEntered = new CountDownLatch(1);
        CountDownLatch releaseConsumer = new CountDownLatch(1);
        DockerLogSubscription subscription = new DockerJavaLabEngine(docker, http)
                .followContainerLogs(container, 20, ignored -> {
                    consumerEntered.countDown();
                    try {
                        releaseConsumer.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                });

        try (ExecutorService execution = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> frame = execution.submit(() -> callback.get().onNext(new Frame(
                    StreamType.STDOUT,
                    "2026-08-27T12:34:56Z line\n"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            assertThat(consumerEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<?> close = execution.submit(subscription::close);

            try {
                assertThatThrownBy(() -> close.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                releaseConsumer.countDown();
            }
            frame.get(1, TimeUnit.SECONDS);
            close.get(1, TimeUnit.SECONDS);
        } finally {
            releaseConsumer.countDown();
            subscription.close();
        }
    }

    @Test
    void keepsPullOpenBetweenChecksAndClosesItOnCancellation() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerHttpClient http = mock(DockerHttpClient.class);
        PullImageCmd pull = mock(PullImageCmd.class);
        AtomicBoolean streamClosed = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(docker.pullImageCmd("busybox:1.37")).thenReturn(pull);
        when(pull.withAuthConfig(any(AuthConfig.class))).thenReturn(pull);
        doAnswer(invocation -> {
                    PullImageResultCallback callback = invocation.getArgument(0);
                    callback.onStart(() -> streamClosed.set(true));
                    return callback;
                })
                .when(pull).exec(any(PullImageResultCallback.class));
        DockerJavaLabEngine engine = new DockerJavaLabEngine(docker, http);

        try (ExecutorService execution = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> result = execution.submit(() -> engine.pullPublicImageAfterConfirmation(
                    "busybox:1.37", Duration.ofSeconds(2), cancelled::get));

            assertThatThrownBy(() -> result.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(streamClosed).isFalse();

            cancelled.set(true);
            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DockerOperationCancelledException.class);
            assertThat(streamClosed).isTrue();
        }
    }

    @Test
    void translatesAnUnavailableDaemonWithoutExposingRawConnectionText() {
        DockerClient docker = mock(DockerClient.class);
        DockerHttpClient http = mock(DockerHttpClient.class);
        when(docker.pingCmd()).thenThrow(new IllegalStateException(
                "connect failed at /private/docker.sock"));
        DockerJavaLabEngine engine = new DockerJavaLabEngine(docker, http);

        assertThatThrownBy(engine::verifyAvailable)
                .isInstanceOfSatisfying(DockerEngineCapabilityException.class, failure -> {
                    assertThat(failure.reason())
                            .isEqualTo(DockerEngineCapabilityException.Reason.UNAVAILABLE);
                    assertThat(failure.getMessage()).contains("Install or start Docker");
                    assertThat(failure.getMessage()).doesNotContain("/private/docker.sock");
                    assertThat(failure.getCause()).isNull();
                });
    }

    @Test
    void translatesRegistryPullFailuresWithoutExposingRawDaemonText() {
        DockerClient docker = mock(DockerClient.class);
        DockerHttpClient http = mock(DockerHttpClient.class);
        PullImageCmd pull = mock(PullImageCmd.class);
        when(docker.pullImageCmd("busybox:1.37")).thenReturn(pull);
        when(pull.withAuthConfig(any(AuthConfig.class))).thenReturn(pull);
        doThrow(new IllegalStateException("denied: raw-registry-detail"))
                .when(pull).exec(any(PullImageResultCallback.class));
        DockerJavaLabEngine engine = new DockerJavaLabEngine(docker, http);

        assertThatThrownBy(() -> engine.pullPublicImageAfterConfirmation(
                "busybox:1.37", Duration.ofSeconds(2), CancellationToken.NONE))
                .isInstanceOfSatisfying(DockerImagePullException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(DockerImagePullException.Reason.FAILED);
                    assertThat(failure.getMessage()).contains("Check its name and public access");
                    assertThat(failure.getMessage()).doesNotContain("raw-registry-detail");
                    assertThat(failure.getCause()).isNull();
                });
    }

    @Test
    void translatesDockerStorageExhaustionWithoutPruningOrRawPaths() {
        DockerClient docker = mock(DockerClient.class);
        DockerHttpClient http = mock(DockerHttpClient.class);
        PullImageCmd pull = mock(PullImageCmd.class);
        when(docker.pullImageCmd("busybox:1.37")).thenReturn(pull);
        when(pull.withAuthConfig(any(AuthConfig.class))).thenReturn(pull);
        doThrow(new IllegalStateException("write /var/lib/docker: no space left on device"))
                .when(pull).exec(any(PullImageResultCallback.class));
        DockerJavaLabEngine engine = new DockerJavaLabEngine(docker, http);

        assertThatThrownBy(() -> engine.pullPublicImageAfterConfirmation(
                "busybox:1.37", Duration.ofSeconds(2), CancellationToken.NONE))
                .isInstanceOf(DockerStorageFullException.class)
                .hasMessageContaining("did not delete or prune anything")
                .hasMessageNotContaining("/var/lib/docker")
                .hasNoCause();
    }

    @Test
    void reportsPullTimeoutAndInterruptionAsTypedSafeFailures() {
        DockerClient docker = mock(DockerClient.class);
        DockerHttpClient http = mock(DockerHttpClient.class);
        PullImageCmd pull = mock(PullImageCmd.class);
        when(docker.pullImageCmd("busybox:1.37")).thenReturn(pull);
        when(pull.withAuthConfig(any(AuthConfig.class))).thenReturn(pull);
        DockerJavaLabEngine engine = new DockerJavaLabEngine(docker, http);

        assertThatThrownBy(() -> engine.pullPublicImageAfterConfirmation(
                "busybox:1.37", Duration.ofSeconds(1), CancellationToken.NONE))
                .isInstanceOfSatisfying(DockerImagePullException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(DockerImagePullException.Reason.TIMED_OUT));

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> engine.pullPublicImageAfterConfirmation(
                    "busybox:1.37", Duration.ofSeconds(2), CancellationToken.NONE))
                    .isInstanceOfSatisfying(DockerImagePullException.class, failure ->
                            assertThat(failure.reason())
                                    .isEqualTo(DockerImagePullException.Reason.INTERRUPTED));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void serializesWorkspaceBindAsNonRecursiveAndPrivate() throws Exception {
        var json = new ObjectMapper().valueToTree(
                DockerJavaLabEngine.nonRecursiveWorkspaceBindOptions());

        assertThat(json.get("NonRecursive").booleanValue()).isTrue();
        assertThat(json.get("Propagation").textValue()).isEqualTo("rprivate");
    }

    private static DockerResourceRecord activeContainer() {
        Instant now = Instant.parse("2026-08-27T12:00:00Z");
        return new DockerResourceRecord(
                "0123456789abcdef0123456789abcdef",
                new LabOwnership("lab-1", "project-1"),
                DockerResourceType.CONTAINER,
                "app",
                Optional.of("container-id"),
                Optional.empty(),
                DockerResourceState.ACTIVE,
                now,
                now);
    }

    private static void mockOwnedInspection(
            DockerClient docker, DockerResourceRecord container) {
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        ContainerConfig config = mock(ContainerConfig.class);
        when(docker.inspectContainerCmd("container-id")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        when(response.getConfig()).thenReturn(config);
        when(config.getLabels()).thenReturn(Map.copyOf(container.labels()));
    }
}
