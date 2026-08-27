package io.labdeck.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import io.labdeck.lab.LabFailureCode;
import io.labdeck.lab.LabRecord;
import io.labdeck.lab.LabState;
import io.labdeck.manifest.ManifestPlan;
import io.labdeck.manifest.ManifestPlanCompiler;
import io.labdeck.manifest.RestrictedManifestParser;
import io.labdeck.persistence.sqlite.LockedSQLiteDataSource;
import io.labdeck.persistence.sqlite.SQLiteDataSourceFactory;
import io.labdeck.persistence.sqlite.SQLiteDockerResourceJournal;
import io.labdeck.persistence.sqlite.SQLiteLabRepository;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "LABDECK_DOCKER_TESTS", matches = "true")
class DockerJavaLabEngineIntegrationTests {

    private static final String IMAGE = "busybox:1.37";
    private static final String RUN_LABEL = "io.labdeck.integration-run";

    @Test
    void createsStartsInspectsAndCleansOnlyTheJournaledLab() throws Exception {
        String run = UUID.randomUUID().toString().replace("-", "");
        String labId = "it-" + run.substring(0, 12);
        String projectId = "project-" + run.substring(0, 12);
        Path runDirectory = Path.of(System.getProperty("user.dir"), "target", "docker-it", run);
        Path workspace = Files.createDirectories(runDirectory.resolve("workspace"));
        Files.writeString(workspace.resolve("workspace-marker.txt"), "workspace survives");

        DockerClient docker = new DockerClientConfiguration().labDeckDockerClient("");
        DockerJavaLabEngine engine = new DockerJavaLabEngine(docker);
        String sentinelId = null;
        LockedSQLiteDataSource dataSource = null;
        SQLiteDockerResourceJournal journal = null;
        DockerLabLifecycle lifecycle = null;
        try {
            engine.verifyAvailable();
            if (engine.inspectImage(IMAGE).isEmpty()) {
                engine.pullPublicImageAfterConfirmation(
                        IMAGE, Duration.ofMinutes(5), CancellationToken.NONE);
            }
            DockerImageMetadata image = engine.inspectImage(IMAGE).orElseThrow();

            sentinelId = docker.createContainerCmd(image.id())
                    .withAuthConfig(new AuthConfig())
                    .withName("labdeck-it-sentinel-" + run.substring(0, 12))
                    .withLabels(Map.of(
                            LabOwnership.MANAGED_LABEL, "true",
                            LabOwnership.LAB_LABEL, labId,
                            LabOwnership.PROJECT_LABEL, projectId,
                            RUN_LABEL, run))
                    .withCmd("sleep", "120")
                    .exec()
                    .getId();
            docker.startContainerCmd(sentinelId).exec();

            dataSource = new SQLiteDataSourceFactory().create(runDirectory.resolve("data"));
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .load()
                    .migrate();
            SQLiteLabRepository labs = new SQLiteLabRepository(dataSource);
            journal = new SQLiteDockerResourceJournal(dataSource);
            LabRecord lab = new LabRecord(
                    labId,
                    projectId,
                    "Docker integration lab",
                    1,
                    workspace,
                    LabState.IMPORTED,
                    0,
                    Instant.now(),
                    Instant.now());
            labs.create(lab);
            lifecycle = new DockerLabLifecycle(engine, journal, labs);

            DockerStartResult started = lifecycle.start(lab, plan(), CancellationToken.NONE);
            DockerResourceRecord volume = journal.findOpen(
                    new LabOwnership(labId, projectId), DockerResourceType.VOLUME, "course-data")
                    .orElseThrow();
            writeVolumeMarker(docker, started.containers().getFirst().id());

            assertThat(started.containers()).singleElement().satisfies(container -> {
                assertThat(container.running()).isTrue();
                assertThat(container.status()).isEqualTo("running");
                assertThat(container.health()).isEqualTo(DockerHealthStatus.HEALTHY);
                assertThat(container.ports()).singleElement().satisfies(port -> {
                    assertThat(port.containerPort()).isEqualTo(8000);
                    assertThat(port.hostAddress()).isEqualTo("127.0.0.1");
                    assertThat(port.hostPort()).isBetween(1024, 65_535);
                });
            });
            DockerPortMapping endpoint = started.containers().getFirst().ports().getFirst();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + endpoint.hostPort() + "/workspace-marker.txt"))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("workspace survives");
            HostConfig appliedLimits = docker.inspectContainerCmd(started.containers().getFirst().id())
                    .exec().getHostConfig();
            assertThat(appliedLimits.getMemory()).isEqualTo(64L * 1024 * 1024);
            assertThat(appliedLimits.getMemorySwap()).isEqualTo(64L * 1024 * 1024);
            assertThat(appliedLimits.getNanoCPUs()).isEqualTo(250_000_000L);
            assertThat(appliedLimits.getOomKillDisable()).isNotEqualTo(Boolean.TRUE);
            assertThat(docker.inspectNetworkCmd().withNetworkId(started.networkId()).exec().getInternal())
                    .isFalse();
            assertThat(docker.inspectVolumeCmd(volume.engineId().orElseThrow()).exec().getLabels())
                    .containsAllEntriesOf(volume.labels());

            LabRecord stopped = lifecycle.stop(labId);

            assertThat(stopped.state()).isEqualTo(LabState.STOPPED);
            assertThat(docker.inspectContainerCmd(sentinelId).exec().getState().getRunning()).isTrue();
            assertThat(docker.inspectContainerCmd(sentinelId).exec().getConfig().getLabels())
                    .containsEntry(RUN_LABEL, run);
            assertThat(Files.readString(workspace.resolve("workspace-marker.txt")))
                    .isEqualTo("workspace survives");
            assertThat(docker.inspectVolumeCmd(volume.engineId().orElseThrow()).exec().getName())
                    .isEqualTo(volume.engineId().orElseThrow());
            assertVolumeMarker(docker, volume, image.id(), run);
            assertThat(docker.listContainersCmd().withShowAll(true)
                    .withLabelFilter(Map.of(
                            LabOwnership.LAB_LABEL, labId,
                            LabOwnership.PROJECT_LABEL, projectId))
                    .exec()).extracting(com.github.dockerjava.api.model.Container::getId)
                    .containsExactly(sentinelId);

            Map<String, String> originalVolumeLabels = docker
                    .inspectVolumeCmd(volume.engineId().orElseThrow()).exec().getLabels();
            assertThat(originalVolumeLabels).containsAllEntriesOf(volume.labels());
            docker.removeVolumeCmd(volume.engineId().orElseThrow()).exec();
            docker.createVolumeCmd()
                    .withName(volume.engineId().orElseThrow())
                    .withDriver("local")
                    .withLabels(originalVolumeLabels)
                    .exec();

            DockerLabLifecycle activeLifecycle = lifecycle;
            assertThatThrownBy(() -> activeLifecycle.start(stopped, plan(), CancellationToken.NONE))
                    .isInstanceOf(DockerOwnershipException.class)
                    .hasMessageContaining("replacement volume");
            assertThat(labs.findById(labId).orElseThrow().state()).isEqualTo(LabState.FAILED);
            assertThat(labs.findRuntimeFailure(labId).orElseThrow().code())
                    .isEqualTo(LabFailureCode.OWNERSHIP_MISMATCH);
            assertThat(docker.inspectContainerCmd(sentinelId).exec().getState().getRunning()).isTrue();
        } finally {
            if (lifecycle != null) {
                try {
                    lifecycle.stop(labId);
                } catch (RuntimeException ignored) {
                    // Exact-ID teardown below handles a failed product cleanup.
                }
                lifecycle.close();
            }
            if (journal != null) {
                cleanupJournaledResources(engine, docker, journal, new LabOwnership(labId, projectId));
                assertNoJournaledEngineResources(docker, labId, projectId);
            }
            if (sentinelId != null) {
                cleanupSentinel(docker, sentinelId, run);
                assertThat(docker.listContainersCmd().withShowAll(true)
                        .withLabelFilter(Map.of(RUN_LABEL, run)).exec()).isEmpty();
            }
            if (dataSource != null) {
                dataSource.close();
            }
            docker.close();
            deleteGeneratedDirectory(runDirectory);
        }
    }

    @Test
    void unhealthyServiceFailsDurablyAndCleansEphemeralResources() throws Exception {
        String name = "Unhealthy Docker integration lab";
        try (DockerScenario scenario = new DockerScenario(name)) {
            ManifestPlan unhealthy = compile("""
                    version: 1
                    name: Unhealthy Docker integration lab
                    workspace:
                      mount: /workspace
                    services:
                      app:
                        image: busybox:1.37
                        command: ["sleep", "60"]
                        healthcheck:
                          command: ["false"]
                          interval: 1s
                          timeout: 1s
                          retries: 1
                    resources:
                      memory: 64MiB
                      cpus: 0.25
                    """);

            assertThatThrownBy(() -> scenario.lifecycle.start(
                    scenario.lab, unhealthy, CancellationToken.NONE))
                    .isInstanceOfSatisfying(DockerServiceReadinessException.class, failure ->
                            assertThat(failure.reason())
                                    .isEqualTo(DockerServiceReadinessException.Reason.UNHEALTHY));

            assertThat(scenario.labs.findById(scenario.labId).orElseThrow().state())
                    .isEqualTo(LabState.FAILED);
            var failure = scenario.labs.findRuntimeFailure(scenario.labId).orElseThrow();
            assertThat(failure.code()).isEqualTo(LabFailureCode.HEALTHCHECK_UNHEALTHY);
            assertThat(failure.service()).contains("app");
            assertThat(failure.safeMessage()).doesNotContain("false");
            assertNoJournaledEngineResources(scenario.docker, scenario.labId, scenario.projectId);
        }
    }

    @Test
    void fixedPortCollisionReturnsAnActionableFailureWithoutClosingTheListener() throws Exception {
        String name = "Port collision Docker integration lab";
        try (ServerSocket listener = new ServerSocket();
                DockerScenario scenario = new DockerScenario(name)) {
            listener.setReuseAddress(false);
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            int occupiedPort = listener.getLocalPort();
            ManifestPlan collision = compile(("""
                    version: 1
                    name: Port collision Docker integration lab
                    workspace:
                      mount: /workspace
                    services:
                      app:
                        image: busybox:1.37
                        command: ["sleep", "60"]
                        ports:
                          - container: 8000
                            host: %d
                    resources:
                      memory: 64MiB
                      cpus: 0.25
                    """).formatted(occupiedPort));

            assertThatThrownBy(() -> scenario.lifecycle.start(
                    scenario.lab, collision, CancellationToken.NONE))
                    .isInstanceOfSatisfying(DockerPortCollisionException.class, failure -> {
                        assertThat(failure.service()).isEqualTo("app");
                        assertThat(failure.hostPorts()).containsExactly(occupiedPort);
                        assertThat(failure.getMessage()).contains("choose a different host port");
                    });

            assertThat(listener.isBound()).isTrue();
            assertThat(listener.isClosed()).isFalse();
            assertThat(scenario.labs.findRuntimeFailure(scenario.labId).orElseThrow().code())
                    .isEqualTo(LabFailureCode.HOST_PORT_IN_USE);
            assertNoJournaledEngineResources(scenario.docker, scenario.labId, scenario.projectId);
        }
    }

    @Test
    void cancellationDuringHealthReadinessStopsAndCleansTheLab() throws Exception {
        String name = "Cancelled Docker integration lab";
        try (DockerScenario scenario = new DockerScenario(name);
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ManifestPlan waiting = compile("""
                    version: 1
                    name: Cancelled Docker integration lab
                    workspace:
                      mount: /workspace
                    services:
                      app:
                        image: busybox:1.37
                        command: ["sleep", "120"]
                        healthcheck:
                          command: ["test", "-f", "/never-ready"]
                          interval: 1s
                          timeout: 1s
                          retries: 20
                    resources:
                      memory: 64MiB
                      cpus: 0.25
                    """);
            Future<?> start = executor.submit(() -> scenario.lifecycle.start(
                    scenario.lab, waiting, CancellationToken.NONE));
            scenario.awaitManagedContainer(Duration.ofSeconds(10));

            Future<LabRecord> stop = executor.submit(() -> scenario.lifecycle.stop(scenario.labId));

            assertThat(stop.get(20, TimeUnit.SECONDS).state()).isEqualTo(LabState.STOPPED);
            assertThatThrownBy(() -> start.get(20, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DockerOperationCancelledException.class);
            assertThat(scenario.labs.findById(scenario.labId).orElseThrow().state())
                    .isEqualTo(LabState.STOPPED);
            assertThat(scenario.labs.findRuntimeFailure(scenario.labId)).isEmpty();
            assertNoJournaledEngineResources(scenario.docker, scenario.labId, scenario.projectId);
        }
    }

    @Test
    void unexpectedRuntimeExitFailsDurablyWithinTheMonitorBound() throws Exception {
        String name = "Runtime exit Docker integration lab";
        try (DockerScenario scenario = new DockerScenario(name)) {
            ManifestPlan exits = compile("""
                    version: 1
                    name: Runtime exit Docker integration lab
                    workspace:
                      mount: /workspace
                    services:
                      app:
                        image: busybox:1.37
                        command: ["sleep", "3"]
                    resources:
                      memory: 64MiB
                      cpus: 0.25
                    """);

            DockerStartResult started = scenario.lifecycle.start(
                    scenario.lab, exits, CancellationToken.NONE);
            assertThat(started.lab().state()).isEqualTo(LabState.RUNNING);

            LabRecord failed = scenario.awaitState(LabState.FAILED, Duration.ofSeconds(8));

            assertThat(failed.state()).isEqualTo(LabState.FAILED);
            assertThat(scenario.labs.findRuntimeFailure(scenario.labId).orElseThrow())
                    .satisfies(failure -> {
                        assertThat(failure.code()).isEqualTo(LabFailureCode.CONTAINER_EXITED);
                        assertThat(failure.service()).contains("app");
                    });
            assertNoJournaledEngineResources(scenario.docker, scenario.labId, scenario.projectId);
        }
    }

    private static ManifestPlan compile(String yaml) {
        return new ManifestPlanCompiler().compile(new RestrictedManifestParser().parse(yaml));
    }

    private static ManifestPlan plan() {
        String yaml = """
                version: 1
                name: Docker integration lab
                workspace:
                  mount: /workspace
                services:
                  app:
                    image: busybox:1.37
                    working_dir: /workspace
                    command: ["httpd", "-f", "-p", "8000", "-h", "/workspace"]
                    ports:
                      - container: 8000
                    healthcheck:
                      command: ["wget", "-q", "-O", "/dev/null", "http://127.0.0.1:8000/workspace-marker.txt"]
                      interval: 1s
                      timeout: 1s
                      retries: 5
                    volumes:
                      - name: course-data
                        target: /data
                resources:
                  memory: 64MiB
                  cpus: 0.25
                """;
        return new ManifestPlanCompiler().compile(new RestrictedManifestParser().parse(yaml));
    }

    private static final class DockerScenario implements AutoCloseable {
        private final String run;
        private final String labId;
        private final String projectId;
        private final Path runDirectory;
        private final DockerClient docker;
        private final DockerJavaLabEngine engine;
        private final LockedSQLiteDataSource dataSource;
        private final SQLiteLabRepository labs;
        private final SQLiteDockerResourceJournal journal;
        private final LabRecord lab;
        private final DockerLabLifecycle lifecycle;

        private DockerScenario(String name) throws Exception {
            run = UUID.randomUUID().toString().replace("-", "");
            labId = "it-" + run.substring(0, 12);
            projectId = "project-" + run.substring(0, 12);
            runDirectory = Path.of(System.getProperty("user.dir"), "target", "docker-it", run);
            Path workspace = Files.createDirectories(runDirectory.resolve("workspace"));
            docker = new DockerClientConfiguration().labDeckDockerClient("");
            engine = new DockerJavaLabEngine(docker);
            engine.verifyAvailable();
            if (engine.inspectImage(IMAGE).isEmpty()) {
                engine.pullPublicImageAfterConfirmation(
                        IMAGE, Duration.ofMinutes(5), CancellationToken.NONE);
            }
            dataSource = new SQLiteDataSourceFactory().create(runDirectory.resolve("data"));
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .load()
                    .migrate();
            labs = new SQLiteLabRepository(dataSource);
            journal = new SQLiteDockerResourceJournal(dataSource);
            lab = new LabRecord(
                    labId,
                    projectId,
                    name,
                    1,
                    workspace,
                    LabState.IMPORTED,
                    0,
                    Instant.now(),
                    Instant.now());
            labs.create(lab);
            lifecycle = new DockerLabLifecycle(engine, journal, labs);
        }

        private void awaitManagedContainer(Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                var containers = docker.listContainersCmd()
                        .withShowAll(true)
                        .withLabelFilter(Map.of(
                                LabOwnership.MANAGED_LABEL, "true",
                                LabOwnership.LAB_LABEL, labId,
                                LabOwnership.PROJECT_LABEL, projectId,
                                LabOwnership.TYPE_LABEL, "container"))
                        .exec();
                if (!containers.isEmpty()) {
                    return;
                }
                Thread.sleep(25);
            }
            throw new IllegalStateException("The integration container did not start in time.");
        }

        private LabRecord awaitState(LabState expected, Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                LabRecord current = labs.findById(labId).orElseThrow();
                if (current.state() == expected) {
                    return current;
                }
                Thread.sleep(25);
            }
            throw new IllegalStateException("The integration lab did not reach " + expected + " in time.");
        }

        @Override
        public void close() throws Exception {
            try {
                try {
                    lifecycle.stop(labId);
                } catch (RuntimeException ignored) {
                    // Exact journal cleanup below handles a failed product cleanup.
                }
                lifecycle.close();
                cleanupJournaledResources(
                        engine, docker, journal, new LabOwnership(labId, projectId));
                assertNoJournaledEngineResources(docker, labId, projectId);
            } finally {
                dataSource.close();
                docker.close();
                deleteGeneratedDirectory(runDirectory);
            }
        }
    }

    private static void writeVolumeMarker(DockerClient docker, String containerId) throws Exception {
        String execId = docker.execCreateCmd(containerId)
                .withPrivileged(false)
                .withAttachStdout(false)
                .withAttachStderr(false)
                .withCmd("touch", "/data/persistent-marker")
                .exec()
                .getId();
        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>()) {
            docker.execStartCmd(execId).withDetach(false).exec(callback);
            assertThat(callback.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(docker.inspectExecCmd(execId).exec().getExitCodeLong()).isZero();
    }

    private static void assertVolumeMarker(
            DockerClient docker, DockerResourceRecord volume, String imageId, String run) throws Exception {
        String checkerId = docker.createContainerCmd(imageId)
                .withAuthConfig(new AuthConfig())
                .withName("labdeck-it-volume-check-" + run.substring(0, 12))
                .withLabels(Map.of(RUN_LABEL, run))
                .withHostConfig(HostConfig.newHostConfig().withMounts(List.of(new Mount()
                        .withType(MountType.VOLUME)
                        .withSource(volume.engineId().orElseThrow())
                        .withTarget("/data"))))
                .withCmd("test", "-f", "/data/persistent-marker")
                .exec()
                .getId();
        try {
            docker.startContainerCmd(checkerId).exec();
            docker.waitContainerCmd(checkerId)
                    .start()
                    .awaitStatusCode(10, TimeUnit.SECONDS);
            assertThat(docker.inspectContainerCmd(checkerId).exec().getState().getExitCodeLong()).isZero();
        } finally {
            var labels = docker.inspectContainerCmd(checkerId).exec().getConfig().getLabels();
            assertThat(labels).containsEntry(RUN_LABEL, run);
            docker.removeContainerCmd(checkerId).withForce(false).withRemoveVolumes(false).exec();
        }
    }

    private static void cleanupJournaledResources(
            DockerJavaLabEngine engine,
            DockerClient docker,
            SQLiteDockerResourceJournal journal,
            LabOwnership ownership) {
        List<DockerResourceRecord> resources = new java.util.ArrayList<>(journal.findOpenByLab(ownership));
        List<DockerResourceRecord> active = new java.util.ArrayList<>();
        for (DockerResourceRecord resource : resources) {
            if (resource.state() == DockerResourceState.RESERVED) {
                continue;
            }
            if (resource.state() == DockerResourceState.DISPATCHED) {
                Optional<DockerCreatedResource> match = engine.reconcileDispatched(resource);
                if (match.isEmpty()) {
                    continue;
                }
                resource = resource.activate(match.orElseThrow(), Instant.now());
            }
            active.add(resource);
        }
        active.stream().filter(resource -> resource.type() == DockerResourceType.CONTAINER).forEach(resource -> {
            try {
                engine.stopContainer(resource, Duration.ofSeconds(2));
                engine.removeContainer(resource);
            } catch (RuntimeException ignored) {
                // The exact resource may already be absent.
            }
        });
        active.stream().filter(resource -> resource.type() == DockerResourceType.NETWORK).forEach(resource -> {
            try {
                engine.removeNetwork(resource);
            } catch (RuntimeException ignored) {
                // The exact resource may already be absent.
            }
        });
        active.stream().filter(resource -> resource.type() == DockerResourceType.VOLUME).forEach(resource -> {
            try {
                var actual = docker.inspectVolumeCmd(resource.engineId().orElseThrow()).exec();
                if (resource.hasExactLabels(actual.getLabels())) {
                    docker.removeVolumeCmd(resource.engineId().orElseThrow()).exec();
                }
            } catch (RuntimeException ignored) {
                // The exact resource may already be absent.
            }
        });
    }

    private static void cleanupSentinel(DockerClient docker, String sentinelId, String run) {
        try {
            var actual = docker.inspectContainerCmd(sentinelId).exec();
            if (run.equals(actual.getConfig().getLabels().get(RUN_LABEL))) {
                if (Boolean.TRUE.equals(actual.getState().getRunning())) {
                    docker.stopContainerCmd(sentinelId).withTimeout(2).exec();
                }
                docker.removeContainerCmd(sentinelId).withForce(false).withRemoveVolumes(false).exec();
            }
        } catch (RuntimeException ignored) {
            // The exact sentinel may already be absent.
        }
    }

    private static void assertNoJournaledEngineResources(
            DockerClient docker, String labId, String projectId) {
        Map<String, String> base = Map.of(
                LabOwnership.MANAGED_LABEL, "true",
                LabOwnership.LAB_LABEL, labId,
                LabOwnership.PROJECT_LABEL, projectId);
        assertThat(docker.listContainersCmd().withShowAll(true)
                .withLabelFilter(withType(base, "container")).exec()).isEmpty();
        assertThat(docker.listNetworksCmd()
                .withFilter("label", labelFilters(withType(base, "network"))).exec()).isEmpty();
        var volumes = docker.listVolumesCmd()
                .withFilter("label", labelFilters(withType(base, "volume"))).exec();
        assertThat(volumes == null ? null : volumes.getVolumes()).isNullOrEmpty();
    }

    private static Map<String, String> withType(Map<String, String> base, String type) {
        Map<String, String> labels = new java.util.HashMap<>(base);
        labels.put(LabOwnership.TYPE_LABEL, type);
        return Map.copyOf(labels);
    }

    private static List<String> labelFilters(Map<String, String> labels) {
        return labels.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .sorted()
                .toList();
    }

    private static void deleteGeneratedDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
