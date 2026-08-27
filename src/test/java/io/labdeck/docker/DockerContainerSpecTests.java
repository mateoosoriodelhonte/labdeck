package io.labdeck.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DockerContainerSpecTests {

    @Test
    void convertsAHealthProbeIntoABoundedReadinessBudget() {
        DockerContainerSpec.HealthProbe probe = new DockerContainerSpec.HealthProbe(
                List.of("wget", "http://127.0.0.1:8000/health"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(3),
                5,
                Duration.ofSeconds(10));

        assertThat(probe.readinessBudget()).isEqualTo(Duration.ofSeconds(55));
        assertThat(probe.toString()).doesNotContain("wget", "127.0.0.1");
    }

    @Test
    void rejectsUnsafeLimitsAndPortBindingsAtTheDockerBoundary() {
        assertThatThrownBy(() -> new DockerContainerSpec.ResourceLimits(1, 2_000_000_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory");
        assertThatThrownBy(() -> new DockerContainerSpec.ResourceLimits(1_000_000_000L, Long.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CPU");
        assertThatThrownBy(() -> new DockerContainerSpec.PublishedPort(
                8_000, Optional.of(80), "tcp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host port");
        assertThatThrownBy(() -> new DockerContainerSpec.PublishedPort(
                8_000, Optional.empty(), "udp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TCP");
    }
}
