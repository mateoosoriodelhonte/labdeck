package io.labdeck.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.labdeck.docker.DockerActiveServiceNotFoundException;
import io.labdeck.docker.DockerLabLifecycle;
import io.labdeck.docker.DockerLogLine;
import io.labdeck.docker.DockerLogSubscription;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LabLogStreamContractTests {

    @Test
    void validatesTheActiveServiceBeforeCommittingHttpSuccess() throws Exception {
        DockerLabLifecycle lifecycle = mock(DockerLabLifecycle.class);
        when(lifecycle.followLogs(eq("lab-1"), eq("app"), eq(20), any()))
                .thenThrow(new DockerActiveServiceNotFoundException());

        try (LabLogStreamService streams = new LabLogStreamService(lifecycle)) {
            mvc(streams).perform(get("/api/v1/labs/lab-1/logs/stream")
                            .queryParam("service", "app")
                            .queryParam("tail", "20"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("LAB_SERVICE_NOT_ACTIVE"));
        }
    }

    @Test
    void rendersJsonLogDataAndAStableTransportErrorReason() throws Exception {
        DockerLabLifecycle lifecycle = mock(DockerLabLifecycle.class);
        AtomicReference<Consumer<DockerLogLine>> consumer = new AtomicReference<>();
        ControlledSubscription subscription = new ControlledSubscription();
        when(lifecycle.followLogs(eq("lab-1"), eq("app"), eq(20), any()))
                .thenAnswer(invocation -> {
                    consumer.set(invocation.getArgument(3));
                    return subscription;
                });

        try (LabLogStreamService streams = new LabLogStreamService(lifecycle)) {
            MockMvc mvc = mvc(streams);
            MvcResult pending = mvc.perform(get("/api/v1/labs/lab-1/logs/stream")
                            .queryParam("service", "app")
                            .queryParam("tail", "20"))
                    .andExpect(request().asyncStarted())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andReturn();

            consumer.get().accept(new DockerLogLine(
                    Instant.parse("2026-08-27T12:00:00Z"),
                    "app",
                    "STDOUT",
                    "ready <not-html>"));
            subscription.fail();

            mvc.perform(asyncDispatch(pending))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("event:log")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "\"text\":\"ready <not-html>\"")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("event:end")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "\"reason\":\"ERROR\"")));
        }
    }

    private static MockMvc mvc(LabLogStreamService streams) {
        return MockMvcBuilders.standaloneSetup(
                        new LabController(mock(LabApiService.class), streams))
                .setControllerAdvice(new ApiExceptionHandler())
                .setAsyncRequestTimeout(2_000)
                .build();
    }

    private static final class ControlledSubscription implements DockerLogSubscription {
        private final AtomicBoolean failed = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private Runnable closeListener = () -> {};

        private void fail() {
            failed.set(true);
            close();
        }

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
            return failed.get();
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
}
