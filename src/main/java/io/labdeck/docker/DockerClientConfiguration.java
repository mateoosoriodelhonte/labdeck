package io.labdeck.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.AuthConfigurations;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.RemoteApiVersion;
import com.github.dockerjava.core.SSLConfig;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DockerClientConfiguration {

    private static final Set<String> LOCAL_SCHEMES = Set.of("unix", "npipe");

    @Bean(destroyMethod = "close")
    DockerClient labDeckDockerClient(@Value("${labdeck.docker.host:}") String configuredHost) {
        DockerClientConfig config = new PublicOnlyDockerClientConfig(resolveDockerHost(configuredHost));
        requireLocalDockerHost(config.getDockerHost());
        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(12)
                .connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(45))
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }

    static URI resolveDockerHost(String configuredHost) {
        if (configuredHost != null && !configuredHost.isBlank()) {
            URI host = URI.create(configuredHost.strip());
            requireLocalDockerHost(host);
            return host;
        }
        String environmentHost = System.getenv("DOCKER_HOST");
        if (environmentHost != null && !environmentHost.isBlank()) {
            URI host = URI.create(environmentHost.strip());
            requireLocalDockerHost(host);
            return host;
        }
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return URI.create("npipe:////./pipe/docker_engine");
        }
        String userHome = System.getProperty("user.home", "");
        List<Path> candidates = new java.util.ArrayList<>();
        candidates.add(Path.of("/var/run/docker.sock"));
        if (!userHome.isBlank()) {
            candidates.add(Path.of(userHome, ".docker", "run", "docker.sock"));
            candidates.add(Path.of(userHome, ".colima", "default", "docker.sock"));
        }
        return candidates.stream()
                .filter(Files::exists)
                .map(path -> URI.create("unix://" + path.toAbsolutePath().normalize()))
                .findFirst()
                .orElseGet(() -> URI.create("unix:///var/run/docker.sock"));
    }

    static void requireLocalDockerHost(URI dockerHost) {
        if (dockerHost == null || !LOCAL_SCHEMES.contains(dockerHost.getScheme())) {
            throw new IllegalStateException(
                    "LabDeck v1 requires a local Docker unix socket or Windows named pipe.");
        }
    }

    private record PublicOnlyDockerClientConfig(URI dockerHost) implements DockerClientConfig {
        private PublicOnlyDockerClientConfig {
            requireLocalDockerHost(dockerHost);
        }

        @Override
        public URI getDockerHost() {
            return dockerHost;
        }

        @Override
        public RemoteApiVersion getApiVersion() {
            return RemoteApiVersion.create(1, 44);
        }

        @Override
        public String getRegistryUsername() {
            return null;
        }

        @Override
        public String getRegistryPassword() {
            return null;
        }

        @Override
        public String getRegistryEmail() {
            return null;
        }

        @Override
        public String getRegistryUrl() {
            return null;
        }

        @Override
        public AuthConfig effectiveAuthConfig(String imageName) {
            return new AuthConfig();
        }

        @Override
        public AuthConfigurations getAuthConfigurations() {
            return new AuthConfigurations();
        }

        @Override
        @SuppressWarnings("deprecation") // docker-java still requires this deprecated interface method.
        public SSLConfig getSSLConfig() {
            return null;
        }
    }
}
