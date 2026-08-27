package io.labdeck.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.transport.DockerHttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DockerJavaLabEngineTests {

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
}
