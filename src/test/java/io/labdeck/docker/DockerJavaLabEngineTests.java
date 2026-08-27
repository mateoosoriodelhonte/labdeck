package io.labdeck.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
    void serializesWorkspaceBindAsNonRecursiveAndPrivate() throws Exception {
        var json = new ObjectMapper().valueToTree(
                DockerJavaLabEngine.nonRecursiveWorkspaceBindOptions());

        assertThat(json.get("NonRecursive").booleanValue()).isTrue();
        assertThat(json.get("Propagation").textValue()).isEqualTo("rprivate");
    }
}
