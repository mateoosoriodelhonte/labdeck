package io.labdeck.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

public final class LoopbackOnlyEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String REQUIRED_ADDRESS = "127.0.0.1";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configuredAddress = environment.getProperty("server.address");
        if (!REQUIRED_ADDRESS.equals(configuredAddress)) {
            throw new IllegalStateException(
                    "LabDeck must bind to 127.0.0.1. Remote server addresses are not supported.");
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
