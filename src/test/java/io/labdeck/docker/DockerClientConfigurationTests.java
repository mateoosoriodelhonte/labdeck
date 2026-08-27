package io.labdeck.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class DockerClientConfigurationTests {

    @Test
    void acceptsOnlyLocalSocketTransports() {
        DockerClientConfiguration.requireLocalDockerHost(URI.create("unix:///var/run/docker.sock"));
        DockerClientConfiguration.requireLocalDockerHost(URI.create("npipe:////./pipe/docker_engine"));

        assertThatThrownBy(() -> DockerClientConfiguration.requireLocalDockerHost(
                        URI.create("tcp://127.0.0.1:2375")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local Docker");
        assertThatThrownBy(() -> DockerClientConfiguration.requireLocalDockerHost(
                        URI.create("https://docker.example.test")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void explicitLocalHostWinsWithoutReadingDockerConfiguration() {
        assertThat(DockerClientConfiguration.resolveDockerHost("unix:///tmp/labdeck-docker.sock"))
                .isEqualTo(URI.create("unix:///tmp/labdeck-docker.sock"));
    }
}
