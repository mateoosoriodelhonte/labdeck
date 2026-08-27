package io.labdeck.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class LoopbackOnlyEnvironmentPostProcessorTests {

    private final LoopbackOnlyEnvironmentPostProcessor processor =
            new LoopbackOnlyEnvironmentPostProcessor();

    @Test
    void acceptsOnlyTheFixedIpv4LoopbackBinding() {
        assertThatCode(() -> process("127.0.0.1")).doesNotThrowAnyException();

        for (String unsafe : new String[] {"0.0.0.0", "::", "::1", "192.168.1.20", "localhost", ""}) {
            assertThatThrownBy(() -> process(unsafe))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("LabDeck must bind to 127.0.0.1. Remote server addresses are not supported.");
        }
        assertThatThrownBy(() -> process((String) null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsASeparateOrRemoteManagementListener() {
        assertThatCode(() -> process(Map.of(
                        "server.address", "127.0.0.1",
                        "management.server.address", "127.0.0.1")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> process(Map.of(
                        "server.address", "127.0.0.1",
                        "management.server.address", "0.0.0.0")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LabDeck management endpoints must use 127.0.0.1. Remote addresses are not supported.");
        assertThatThrownBy(() -> process(Map.of(
                        "server.address", "127.0.0.1",
                        "management.server.port", "8788")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LabDeck management endpoints must use the main local server port.");
    }

    @Test
    void registersTheGuardAsAnEarlyEnvironmentProcessor() throws Exception {
        Properties factories = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/META-INF/spring.factories")) {
            factories.load(input);
        }

        assertThat(factories.getProperty("org.springframework.boot.EnvironmentPostProcessor"))
                .isEqualTo(LoopbackOnlyEnvironmentPostProcessor.class.getName());
    }

    private void process(String address) {
        StandardEnvironment environment = new StandardEnvironment();
        if (address != null) {
            process(Map.of("server.address", address));
            return;
        }
        processor.postProcessEnvironment(environment, null);
    }

    private void process(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        processor.postProcessEnvironment(environment, null);
    }
}
