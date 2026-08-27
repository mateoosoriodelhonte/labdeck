package io.labdeck.docker;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.BindOptions;
import com.github.dockerjava.api.model.BindPropagation;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HealthCheck;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DockerJavaLabEngine implements DockerEnginePort {

    private static final Pattern VOLUME_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LOG_TAIL = 500;
    private static final int MAX_LOG_LINE_CHARS = 16_384;
    private static final int LOG_HISTORY_BYTES = 256 * 1_024;
    private static final int LOG_STREAM_LINES = 2_000;
    private static final int LOG_STREAM_BYTES = 1_024 * 1_024;
    private static final Duration LOG_HISTORY_TIMEOUT = Duration.ofSeconds(5);
    private static final Map<String, String> LOG_OPTIONS = Map.of(
            "max-size", "10m",
            "max-file", "3",
            "compress", "true");

    private final DockerClient docker;
    private final DockerHttpClient http;

    @Autowired
    public DockerJavaLabEngine(DockerClient docker) {
        this(docker, requireHttpClient(docker));
    }

    DockerJavaLabEngine(DockerClient docker, DockerHttpClient http) {
        this.docker = Objects.requireNonNull(docker, "docker");
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public void verifyAvailable() {
        try {
            docker.pingCmd().exec();
        } catch (RuntimeException failure) {
            throw new DockerEngineCapabilityException(
                    DockerEngineCapabilityException.Reason.UNAVAILABLE);
        }
    }

    @Override
    public void verifyLocalPortPublishingSupported() {
        String version;
        try {
            version = docker.versionCmd().exec().getVersion();
        } catch (RuntimeException failure) {
            throw new DockerEngineCapabilityException(
                    DockerEngineCapabilityException.Reason.UNAVAILABLE);
        }
        int separator = version == null ? -1 : version.indexOf('.');
        if (separator < 1) {
            throw new DockerEngineCapabilityException(
                    DockerEngineCapabilityException.Reason.VERSION_UNSUPPORTED);
        }
        int major;
        try {
            major = Integer.parseInt(version.substring(0, separator));
        } catch (NumberFormatException exception) {
            throw new DockerEngineCapabilityException(
                    DockerEngineCapabilityException.Reason.VERSION_UNSUPPORTED);
        }
        if (major < 28) {
            throw new DockerEngineCapabilityException(
                    DockerEngineCapabilityException.Reason.VERSION_UNSUPPORTED);
        }
    }

    @Override
    public void verifyResourceLimitsSupported() {
        var info = dockerInfo();
        if (!Boolean.TRUE.equals(info.getMemoryLimit())
                || !Boolean.TRUE.equals(info.getSwapLimit())
                || !Boolean.TRUE.equals(info.getCpuCfsQuota())) {
            throw new DockerEngineCapabilityException(
                    DockerEngineCapabilityException.Reason.RESOURCE_LIMITS_UNSUPPORTED);
        }
    }

    private com.github.dockerjava.api.model.Info dockerInfo() {
        try {
            return docker.infoCmd().exec();
        } catch (RuntimeException failure) {
            throw new DockerEngineCapabilityException(
                    DockerEngineCapabilityException.Reason.UNAVAILABLE);
        }
    }

    @Override
    public Optional<DockerImageMetadata> inspectImage(String reference) {
        requireImageReference(reference);
        try {
            InspectImageResponse image = docker.inspectImageCmd(reference).exec();
            ContainerConfig config = image.getConfig();
            Map<String, ?> declaredVolumes = config == null ? null : config.getVolumes();
            Set<String> targets = declaredVolumes == null ? Set.of() : Set.copyOf(declaredVolumes.keySet());
            HealthCheck health = config == null ? null : config.getHealthcheck();
            boolean healthConfigured = health != null
                    && health.getTest() != null
                    && !health.getTest().isEmpty()
                    && !health.getTest().equals(List.of("NONE"));
            return Optional.of(new DockerImageMetadata(
                    image.getId(), image.getSize() == null ? 0 : image.getSize(), targets, healthConfigured));
        } catch (NotFoundException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void pullPublicImageAfterConfirmation(
            String reference, Duration timeout, CancellationToken cancellation) {
        requireImageReference(reference);
        requireTimeout(timeout, Duration.ofSeconds(1), Duration.ofMinutes(30));
        cancellation = cancellation == null ? CancellationToken.NONE : cancellation;
        long deadline = System.nanoTime() + timeout.toNanos();
        try (ExecutorService waiter = Executors.newVirtualThreadPerTaskExecutor();
                PullImageResultCallback callback = new PullImageResultCallback()) {
            docker.pullImageCmd(reference)
                    .withAuthConfig(new AuthConfig())
                    .exec(callback);
            Future<?> completion = waiter.submit(() -> {
                callback.awaitCompletion();
                return null;
            });
            while (true) {
                cancellation.throwIfCancellationRequested();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new DockerImagePullException(DockerImagePullException.Reason.TIMED_OUT);
                }
                long waitNanos = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100));
                try {
                    completion.get(waitNanos, TimeUnit.NANOSECONDS);
                    return;
                } catch (TimeoutException expectedWhilePulling) {
                    // Recheck timeout and cancellation without closing the active callback.
                } catch (ExecutionException failure) {
                    throw propagatePullFailure(failure.getCause());
                }
            }
        } catch (DockerOperationCancelledException | DockerImagePullException
                | DockerStorageFullException expected) {
            throw expected;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DockerImagePullException(DockerImagePullException.Reason.INTERRUPTED);
        } catch (IOException exception) {
            throw new DockerImagePullException(DockerImagePullException.Reason.FAILED);
        } catch (RuntimeException failure) {
            throw classifyPullFailure(failure);
        }
    }

    @Override
    public Optional<DockerCreatedResource> reconcileDispatched(DockerResourceRecord dispatched) {
        requireDispatched(dispatched);
        List<DockerCreatedResource> matches = switch (dispatched.type()) {
            case CONTAINER -> docker.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(dispatched.labels())
                    .exec().stream()
                    .map(com.github.dockerjava.api.model.Container::getId)
                    .filter(Objects::nonNull)
                    .filter(id -> hasExactContainerIdentity(id, dispatched))
                    .map(DockerCreatedResource::withImmutableId)
                    .toList();
            case NETWORK -> docker.listNetworksCmd()
                    .withFilter("label", labelFilters(dispatched.labels()))
                    .exec().stream()
                    .map(com.github.dockerjava.api.model.Network::getId)
                    .filter(Objects::nonNull)
                    .filter(id -> hasExactNetworkIdentity(id, dispatched))
                    .map(DockerCreatedResource::withImmutableId)
                    .toList();
            case VOLUME -> {
                var response = docker.listVolumesCmd()
                        .withFilter("label", labelFilters(dispatched.labels()))
                        .exec();
                var volumes = response == null || response.getVolumes() == null
                        ? List.<com.github.dockerjava.api.command.InspectVolumeResponse>of()
                        : response.getVolumes();
                yield volumes.stream()
                        .map(com.github.dockerjava.api.command.InspectVolumeResponse::getName)
                        .filter(Objects::nonNull)
                        .filter(id -> engineName(dispatched).equals(id))
                        .filter(id -> hasExactVolumeLabels(id, dispatched))
                        .map(id -> DockerCreatedResource.identified(id, inspectVolumeIdentity(id)))
                        .toList();
            }
        };
        if (matches.size() > 1) {
            throw new DockerOwnershipException(
                    "More than one Docker resource matched a dispatched ownership token.");
        }
        return matches.stream().findFirst();
    }

    @Override
    public DockerCreatedResource createNetwork(DockerResourceRecord dispatched) {
        requireType(dispatched, DockerResourceType.NETWORK, DockerResourceState.DISPATCHED);
        try {
            requireExpectedNameAvailable(dispatched);
        } catch (RuntimeException preflightFailure) {
            throw createWithoutOwnedResource(preflightFailure);
        }
        String id;
        try {
            id = docker.createNetworkCmd()
                    .withName(engineName(dispatched))
                    .withDriver("bridge")
                    .withInternal(false)
                    .withAttachable(false)
                    .withCheckDuplicate(false)
                    .withLabels(dispatched.labels())
                    .exec()
                    .getId();
        } catch (RuntimeException failure) {
            throw classifyStorageFailure(failure);
        }
        requireCreatedId(id);
        var created = docker.inspectNetworkCmd().withNetworkId(id).exec();
        if (!dispatched.hasExactLabels(created.getLabels())
                || !"bridge".equals(created.getDriver())
                || Boolean.TRUE.equals(created.getInternal())
                || Boolean.TRUE.equals(created.isAttachable())) {
            throw new DockerOwnershipException("Docker did not create the planned private bridge network.");
        }
        return DockerCreatedResource.withImmutableId(id);
    }

    @Override
    public DockerCreatedResource createVolume(DockerResourceRecord dispatched) {
        requireType(dispatched, DockerResourceType.VOLUME, DockerResourceState.DISPATCHED);
        try {
            requireExpectedNameAvailable(dispatched);
        } catch (RuntimeException preflightFailure) {
            throw createWithoutOwnedResource(preflightFailure);
        }
        String id;
        try {
            id = docker.createVolumeCmd()
                    .withName(engineName(dispatched))
                    .withDriver("local")
                    .withLabels(dispatched.labels())
                    .exec()
                    .getName();
        } catch (RuntimeException failure) {
            throw classifyStorageFailure(failure);
        }
        requireCreatedId(id);
        var created = docker.inspectVolumeCmd(id).exec();
        if (!dispatched.hasExactLabels(created.getLabels()) || !"local".equals(created.getDriver())) {
            throw createWithoutOwnedResource(new DockerOwnershipException(
                    "Docker did not create the planned local named volume."));
        }
        return DockerCreatedResource.identified(id, inspectVolumeIdentity(id));
    }

    @Override
    public DockerCreatedResource createContainer(
            DockerResourceRecord dispatched, DockerContainerSpec specification) {
        requireType(dispatched, DockerResourceType.CONTAINER, DockerResourceState.DISPATCHED);
        Objects.requireNonNull(specification, "specification");
        try {
            requireExpectedNameAvailable(dispatched);
            requireImageVolumesCovered(specification);
            specification.workspace().verifyUnchanged();
        } catch (RuntimeException preflightFailure) {
            throw createWithoutOwnedResource(preflightFailure);
        }

        List<Mount> mounts = new ArrayList<>();
        mounts.add(new Mount()
                .withType(MountType.BIND)
                .withSource(specification.workspace().path().toString())
                .withTarget(specification.workspaceTarget())
                .withReadOnly(false)
                .withBindOptions(nonRecursiveWorkspaceBindOptions()));
        specification.namedMounts().forEach(volume -> mounts.add(new Mount()
                .withType(MountType.VOLUME)
                .withSource(volume.volumeId())
                .withTarget(volume.target())
                .withReadOnly(volume.readOnly())));

        List<ExposedPort> exposedPorts = specification.publishedPorts().stream()
                .map(port -> ExposedPort.tcp(port.containerPort()))
                .toList();
        Ports portBindings = new Ports();
        specification.publishedPorts().forEach(port -> portBindings.bind(
                ExposedPort.tcp(port.containerPort()),
                port.hostPort().isPresent()
                        ? Ports.Binding.bindIpAndPort("127.0.0.1", port.hostPort().orElseThrow())
                        : new Ports.Binding("127.0.0.1", "")));

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withNetworkMode(specification.networkId())
                .withMounts(mounts)
                .withPortBindings(portBindings)
                .withMemory(specification.resourceLimits().memoryBytes())
                .withMemorySwap(specification.resourceLimits().memoryBytes())
                .withNanoCPUs(specification.resourceLimits().nanoCpus())
                .withOomKillDisable(false)
                .withAutoRemove(false)
                .withPrivileged(false)
                .withPublishAllPorts(false)
                .withLogConfig(new LogConfig(LogConfig.LoggingType.LOCAL, LOG_OPTIONS))
                .withRestartPolicy(RestartPolicy.noRestart());
        Map<String, String> labels = new java.util.LinkedHashMap<>(dispatched.labels());
        labels.put(LabOwnership.IMAGE_REFERENCE_LABEL, specification.imageReference());
        CreateContainerCmd command = docker.createContainerCmd(specification.image())
                .withAuthConfig(new AuthConfig())
                .withName(engineName(dispatched))
                .withLabels(Map.copyOf(labels))
                .withAliases(dispatched.logicalName())
                .withWorkingDir(specification.workingDirectory())
                .withEnv(environment(specification.environment()))
                .withExposedPorts(exposedPorts)
                .withHostConfig(hostConfig);
        specification.healthProbe().ifPresent(ignored -> command.withHealthcheck(healthCheck(specification)));
        if (!specification.command().isEmpty()) {
            command.withCmd(specification.command());
        }
        String id;
        try {
            id = command.exec().getId();
        } catch (RuntimeException failure) {
            throw classifyStorageFailure(failure);
        }
        requireCreatedId(id);
        DockerCreatedResource result = DockerCreatedResource.withImmutableId(id);
        InspectContainerResponse created = inspectOwnedContainer(dispatched.activate(result, dispatched.updatedAt()));
        requireContainerShape(created, specification);
        return result;
    }

    @Override
    public DockerContainerView inspectContainer(
            DockerResourceRecord active, DockerContainerSpec specification) {
        requireType(active, DockerResourceType.CONTAINER, DockerResourceState.ACTIVE);
        Objects.requireNonNull(specification, "specification");
        InspectContainerResponse inspection = inspectOwnedContainer(active);
        requireContainerShape(inspection, specification);
        var state = inspection.getState();
        String status = state == null || state.getStatus() == null ? "unknown" : state.getStatus();
        boolean running = state != null && Boolean.TRUE.equals(state.getRunning());
        OptionalInt exitCode = !running && state != null && state.getExitCodeLong() != null
                ? OptionalInt.of(Math.toIntExact(state.getExitCodeLong()))
                : OptionalInt.empty();
        DockerHealthStatus health = healthStatus(state == null ? null : state.getHealth());
        String name = inspection.getName() == null ? "" : inspection.getName().replaceFirst("^/", "");
        String image = inspection.getConfig() == null || inspection.getConfig().getImage() == null
                ? "unknown" : inspection.getConfig().getImage();
        return new DockerContainerView(
                inspection.getId(), active.logicalName(), name, image, status, running,
                exitCode, health, inspectPublishedPorts(inspection, specification));
    }

    @Override
    public DockerContainerView inspectContainerSnapshot(DockerResourceRecord active) {
        requireType(active, DockerResourceType.CONTAINER, DockerResourceState.ACTIVE);
        InspectContainerResponse inspection = inspectOwnedContainer(active);
        var state = inspection.getState();
        String status = state == null || state.getStatus() == null ? "unknown" : state.getStatus();
        boolean running = state != null && Boolean.TRUE.equals(state.getRunning());
        OptionalInt exitCode = !running && state != null && state.getExitCodeLong() != null
                ? OptionalInt.of(Math.toIntExact(state.getExitCodeLong()))
                : OptionalInt.empty();
        DockerHealthStatus health = healthStatus(state == null ? null : state.getHealth());
        String name = inspection.getName() == null ? "" : inspection.getName().replaceFirst("^/", "");
        String image = inspection.getConfig() == null || inspection.getConfig().getImage() == null
                ? "unknown" : inspection.getConfig().getImage();
        return new DockerContainerView(
                inspection.getId(), active.logicalName(), name, image, status, running,
                exitCode, health, inspectSnapshotPublishedPorts(inspection));
    }

    @Override
    public DockerServiceObservation inspectContainerObservation(
            DockerResourceRecord active, Map<String, String> ownedVolumes) {
        requireType(active, DockerResourceType.CONTAINER, DockerResourceState.ACTIVE);
        Objects.requireNonNull(ownedVolumes, "ownedVolumes");
        InspectContainerResponse inspection = inspectOwnedContainer(active);
        DockerContainerView container = snapshotView(active, inspection);
        Optional<String> imageReference = safeImageReference(inspection);
        OptionalLong imageSize = inspectImageSize(inspection);
        OptionalLong writableSize = inspectWritableLayerSize(active);
        DockerContainerMetrics metrics = inspectMetrics(active, inspection, container.running());
        return new DockerServiceObservation(
                container,
                imageReference,
                metrics,
                imageSize,
                writableSize,
                observedVolumeMounts(inspection, ownedVolumes));
    }

    private static List<DockerVolumeMountObservation> observedVolumeMounts(
            InspectContainerResponse inspection, Map<String, String> ownedVolumes) {
        if (inspection.getMounts() == null || inspection.getMounts().isEmpty()) {
            return List.of();
        }
        List<DockerVolumeMountObservation> mounts = new ArrayList<>();
        for (InspectContainerResponse.Mount mount : inspection.getMounts()) {
            String engineName = mount == null ? null : mount.getName();
            String logicalName = engineName == null ? null : ownedVolumes.get(engineName);
            if (logicalName == null) {
                continue;
            }
            if (mount.getDestination() == null) {
                throw new DockerOwnershipException(
                        "Docker did not report the owned volume mount target.");
            }
            mounts.add(new DockerVolumeMountObservation(
                    logicalName,
                    mount.getDestination().getPath(),
                    !Boolean.TRUE.equals(mount.getRW())));
        }
        return mounts.stream()
                .sorted(java.util.Comparator.comparing(DockerVolumeMountObservation::volume)
                        .thenComparing(DockerVolumeMountObservation::target))
                .toList();
    }

    @Override
    public DockerVolumeObservation inspectVolumeObservation(DockerResourceRecord active) {
        requireType(active, DockerResourceType.VOLUME, DockerResourceState.ACTIVE);
        inspectOwnedVolume(active);
        return new DockerVolumeObservation(
                active.logicalName(), OptionalLong.empty(), "UNAVAILABLE");
    }

    @Override
    public void verifyNetwork(DockerResourceRecord active) {
        requireType(active, DockerResourceType.NETWORK, DockerResourceState.ACTIVE);
        inspectOwnedNetwork(active);
    }

    @Override
    public DockerLogBatch readContainerLogs(DockerResourceRecord active, int tail) {
        requireLogRequest(active, tail);
        List<DockerLogLine> lines = new ArrayList<>();
        BoundedDockerLogCallback callback = openLogCallback(
                active, tail, false, tail, LOG_HISTORY_BYTES, lines::add);
        try {
            boolean completed = callback.await(LOG_HISTORY_TIMEOUT);
            if (!completed) {
                callback.markTruncated();
            }
            callback.close();
            callback.throwIfFailed();
            return new DockerLogBatch(lines, callback.truncated());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DockerLogAccessException();
        } finally {
            callback.close();
        }
    }

    @Override
    public DockerLogSubscription followContainerLogs(
            DockerResourceRecord active, int tail, Consumer<DockerLogLine> consumer) {
        requireLogRequest(active, tail);
        Objects.requireNonNull(consumer, "consumer");
        return openLogCallback(
                active, tail, true, LOG_STREAM_LINES, LOG_STREAM_BYTES, consumer);
    }

    @Override
    public void startContainer(
            DockerResourceRecord active, DockerContainerSpec specification) {
        requireType(active, DockerResourceType.CONTAINER, DockerResourceState.ACTIVE);
        Objects.requireNonNull(specification, "specification");
        requireContainerShape(inspectOwnedContainer(active), specification);
        try {
            docker.startContainerCmd(active.engineId().orElseThrow()).exec();
        } catch (RuntimeException ambiguousFailure) {
            InspectContainerResponse reconciled = inspectOwnedContainer(active);
            if (reconciled.getState() != null
                    && Boolean.TRUE.equals(reconciled.getState().getRunning())) {
                return;
            }
            List<Integer> fixedPorts = specification.publishedPorts().stream()
                    .flatMap(port -> port.hostPort().stream())
                    .sorted()
                    .toList();
            if (!fixedPorts.isEmpty() && isPortCollision(ambiguousFailure)) {
                throw new DockerPortCollisionException(active.logicalName(), fixedPorts, ambiguousFailure);
            }
            throw classifyStorageFailure(ambiguousFailure);
        }
    }

    @Override
    public void stopContainer(DockerResourceRecord active, Duration timeout) {
        requireType(active, DockerResourceType.CONTAINER, DockerResourceState.ACTIVE);
        requireTimeout(timeout, Duration.ofSeconds(1), Duration.ofMinutes(2));
        InspectContainerResponse inspection = inspectOwnedContainer(active);
        if (inspection.getState() != null && Boolean.TRUE.equals(inspection.getState().getRunning())) {
            try {
                docker.stopContainerCmd(active.engineId().orElseThrow())
                        .withTimeout(Math.toIntExact(timeout.toSeconds()))
                        .exec();
            } catch (RuntimeException ambiguousFailure) {
                InspectContainerResponse reconciled = inspectOwnedContainer(active);
                if (reconciled.getState() != null
                        && !Boolean.TRUE.equals(reconciled.getState().getRunning())) {
                    return;
                }
                throw ambiguousFailure;
            }
        }
    }

    @Override
    public void removeContainer(DockerResourceRecord active) {
        requireType(active, DockerResourceType.CONTAINER, DockerResourceState.ACTIVE);
        inspectOwnedContainer(active);
        try {
            docker.removeContainerCmd(active.engineId().orElseThrow())
                    .withForce(false)
                    .withRemoveVolumes(false)
                    .exec();
        } catch (RuntimeException ambiguousFailure) {
            if (containerIsAbsent(active.engineId().orElseThrow())) {
                return;
            }
            inspectOwnedContainer(active);
            throw ambiguousFailure;
        }
    }

    @Override
    public void removeNetwork(DockerResourceRecord active) {
        requireType(active, DockerResourceType.NETWORK, DockerResourceState.ACTIVE);
        var network = inspectOwnedNetwork(active);
        if (network.getContainers() != null && !network.getContainers().isEmpty()) {
            throw new DockerOwnershipException(
                    "The LabDeck network still has attached containers and cannot be removed safely.");
        }
        try {
            docker.removeNetworkCmd(active.engineId().orElseThrow()).exec();
        } catch (RuntimeException ambiguousFailure) {
            if (networkIsAbsent(active.engineId().orElseThrow())) {
                return;
            }
            inspectOwnedNetwork(active);
            throw ambiguousFailure;
        }
    }

    @Override
    public void verifyVolume(DockerResourceRecord active) {
        requireType(active, DockerResourceType.VOLUME, DockerResourceState.ACTIVE);
        var volume = inspectOwnedVolume(active);
        if (!"local".equals(volume.getDriver())) {
            throw new DockerOwnershipException("The journaled Docker volume does not use the local driver.");
        }
        if (!active.engineIdentity().orElseThrow()
                .equals(inspectVolumeIdentity(active.engineId().orElseThrow()))) {
            throw new DockerOwnershipException(
                    "The Docker volume name now refers to a replacement volume.");
        }
    }

    private void requireImageVolumesCovered(DockerContainerSpec specification) {
        DockerImageMetadata image = inspectImage(specification.image())
                .orElseThrow(() -> new IllegalStateException("The required image is not available locally."));
        Set<String> covered = Set.copyOf(specification.coveredImageVolumeTargets());
        Set<String> uncovered = image.declaredVolumeTargets().stream()
                .filter(target -> !covered.contains(target))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!uncovered.isEmpty()) {
            throw new IllegalStateException(
                    "The image declares volumes that need explicit LabDeck mounts: "
                            + String.join(", ", new java.util.TreeSet<>(uncovered)));
        }
    }

    private void requireExpectedNameAvailable(DockerResourceRecord reserved) {
        String expected = engineName(reserved);
        boolean collision = switch (reserved.type()) {
            case CONTAINER -> docker.listContainersCmd().withShowAll(true).withNameFilter(List.of(expected))
                    .exec().stream().anyMatch(container -> hasExactName(container.getNames(), expected));
            case NETWORK -> docker.listNetworksCmd().withNameFilter(expected).exec().stream()
                    .anyMatch(network -> expected.equals(network.getName()));
            case VOLUME -> {
                try {
                    docker.inspectVolumeCmd(expected).exec();
                    yield true;
                } catch (NotFoundException exception) {
                    yield false;
                }
            }
        };
        if (collision) {
            throw new DockerOwnershipException("The planned Docker resource name is already in use.");
        }
    }

    private InspectContainerResponse inspectOwnedContainer(DockerResourceRecord expected) {
        try {
            InspectContainerResponse actual = docker.inspectContainerCmd(expected.engineId().orElseThrow()).exec();
            if (!expected.hasExactLabels(actual.getConfig() == null ? null : actual.getConfig().getLabels())) {
                throw ownershipMismatch();
            }
            return actual;
        } catch (NotFoundException exception) {
            throw staleResourceId(exception);
        }
    }

    private com.github.dockerjava.api.model.Network inspectOwnedNetwork(DockerResourceRecord expected) {
        try {
            var actual = docker.inspectNetworkCmd().withNetworkId(expected.engineId().orElseThrow()).exec();
            if (!expected.hasExactLabels(actual.getLabels())) {
                throw ownershipMismatch();
            }
            return actual;
        } catch (NotFoundException exception) {
            throw staleResourceId(exception);
        }
    }

    private com.github.dockerjava.api.command.InspectVolumeResponse inspectOwnedVolume(
            DockerResourceRecord expected) {
        try {
            var actual = docker.inspectVolumeCmd(expected.engineId().orElseThrow()).exec();
            if (!expected.hasExactLabels(actual.getLabels())) {
                throw ownershipMismatch();
            }
            return actual;
        } catch (NotFoundException exception) {
            throw staleResourceId(exception);
        }
    }

    private static void requireContainerShape(
            InspectContainerResponse actual, DockerContainerSpec expected) {
        if (actual.getConfig() == null
                || !expected.image().equals(actual.getConfig().getImage())
                || actual.getConfig().getLabels() == null
                || !expected.imageReference().equals(
                        actual.getConfig().getLabels().get(LabOwnership.IMAGE_REFERENCE_LABEL))
                || actual.getHostConfig() == null
                || !expected.networkId().equals(actual.getHostConfig().getNetworkMode())
                || !Long.valueOf(expected.resourceLimits().memoryBytes())
                        .equals(actual.getHostConfig().getMemory())
                || !Long.valueOf(expected.resourceLimits().memoryBytes())
                        .equals(actual.getHostConfig().getMemorySwap())
                || !Long.valueOf(expected.resourceLimits().nanoCpus())
                        .equals(actual.getHostConfig().getNanoCPUs())
                || Boolean.TRUE.equals(actual.getHostConfig().getPublishAllPorts())
                || Boolean.TRUE.equals(actual.getHostConfig().getOomKillDisable())
                || actual.getHostConfig().getLogConfig() == null
                || actual.getHostConfig().getLogConfig().getType() != LogConfig.LoggingType.LOCAL
                || !LOG_OPTIONS.equals(actual.getHostConfig().getLogConfig().getConfig())) {
            throw new DockerOwnershipException(
                    "Docker created a container with different image, network, logging, or resource limits than planned.");
        }
        requirePortBindingShape(actual, expected);
        requireHealthCheckShape(actual, expected);
        Map<String, InspectContainerResponse.Mount> mountsByTarget = actual.getMounts() == null
                ? Map.of()
                : actual.getMounts().stream().collect(java.util.stream.Collectors.toMap(
                        mount -> mount.getDestination().getPath(),
                        java.util.function.Function.identity(),
                        (first, second) -> first));
        var workspaceMount = mountsByTarget.get(expected.workspaceTarget());
        if (workspaceMount == null
                || !expected.workspace().path().toString().equals(workspaceMount.getSource())
                || !Boolean.TRUE.equals(workspaceMount.getRW())) {
            throw new DockerOwnershipException("Docker did not create the approved workspace mount.");
        }
        for (DockerContainerSpec.NamedMount named : expected.namedMounts()) {
            var actualMount = mountsByTarget.get(named.target());
            if (actualMount == null
                    || !named.volumeId().equals(actualMount.getName())
                    || Boolean.TRUE.equals(actualMount.getRW()) == named.readOnly()) {
                throw new DockerOwnershipException("Docker did not create the planned named-volume mount.");
            }
        }
    }

    private static void requirePortBindingShape(
            InspectContainerResponse actual, DockerContainerSpec expected) {
        Set<ExposedPort> expectedExposed = expected.publishedPorts().stream()
                .map(port -> ExposedPort.tcp(port.containerPort()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        ExposedPort[] actualExposedArray = actual.getConfig().getExposedPorts();
        Set<ExposedPort> actualExposed = actualExposedArray == null
                ? Set.of() : Set.of(actualExposedArray);
        if (!actualExposed.containsAll(expectedExposed)) {
            throw new DockerOwnershipException("Docker did not preserve every planned exposed port.");
        }
        Map<ExposedPort, Ports.Binding[]> actualBindings = actual.getHostConfig().getPortBindings() == null
                ? Map.of() : actual.getHostConfig().getPortBindings().getBindings();
        if (actualBindings.size() != expected.publishedPorts().size()) {
            throw new DockerOwnershipException("Docker created different host port bindings than planned.");
        }
        for (DockerContainerSpec.PublishedPort port : expected.publishedPorts()) {
            Ports.Binding[] bindings = actualBindings.get(ExposedPort.tcp(port.containerPort()));
            if (bindings == null || bindings.length != 1
                    || !"127.0.0.1".equals(bindings[0].getHostIp())) {
                throw new DockerOwnershipException("Docker did not bind the planned port to localhost.");
            }
            String actualHostPort = bindings[0].getHostPortSpec();
            if (port.hostPort().isPresent()
                    && !Integer.toString(port.hostPort().orElseThrow()).equals(actualHostPort)) {
                throw new DockerOwnershipException("Docker created a different fixed host port than planned.");
            }
            if (port.hostPort().isEmpty() && actualHostPort != null && !actualHostPort.isBlank()) {
                throw new DockerOwnershipException("Docker did not preserve the planned dynamic host port.");
            }
        }
    }

    private static void requireHealthCheckShape(
            InspectContainerResponse actual, DockerContainerSpec expected) {
        HealthCheck actualHealth = actual.getConfig().getHealthcheck();
        if (expected.healthProbe().isPresent()) {
            DockerContainerSpec.HealthProbe probe = expected.healthProbe().orElseThrow();
            List<String> expectedTest = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of("CMD"), probe.command().stream())
                    .toList();
            if (actualHealth == null || !expectedTest.equals(actualHealth.getTest())) {
                throw new DockerOwnershipException("Docker created a different health command than planned.");
            }
            if (!Long.valueOf(probe.interval().toNanos()).equals(actualHealth.getInterval())
                    || !Long.valueOf(probe.timeout().toNanos()).equals(actualHealth.getTimeout())
                    || !Integer.valueOf(probe.retries()).equals(actualHealth.getRetries())
                    || normalizedNanoseconds(actualHealth.getStartPeriod())
                            != probe.startPeriod().toNanos()
                    || !Long.valueOf(probe.interval().toNanos()).equals(actualHealth.getStartInterval())) {
                throw new DockerOwnershipException("Docker created different health timing than planned.");
            }
            return;
        }
        boolean actualConfigured = actualHealth != null
                && actualHealth.getTest() != null
                && !actualHealth.getTest().isEmpty()
                && !actualHealth.getTest().equals(List.of("NONE"));
        if (actualConfigured != expected.imageHealthCheckConfigured()) {
            throw new DockerOwnershipException("Docker did not preserve the image health policy.");
        }
    }

    private static HealthCheck healthCheck(DockerContainerSpec specification) {
        return specification.healthProbe()
                .map(probe -> new HealthCheck()
                        .withTest(java.util.stream.Stream.concat(
                                        java.util.stream.Stream.of("CMD"), probe.command().stream())
                                .toList())
                        .withInterval(probe.interval().toNanos())
                        .withTimeout(probe.timeout().toNanos())
                        .withRetries(probe.retries())
                        .withStartPeriod(probe.startPeriod().toNanos())
                        .withStartInterval(probe.interval().toNanos()))
                .orElseThrow();
    }

    private static long normalizedNanoseconds(Long value) {
        return value == null ? 0L : value;
    }

    private static DockerHealthStatus healthStatus(com.github.dockerjava.api.command.HealthState health) {
        if (health == null || health.getStatus() == null) {
            return DockerHealthStatus.NONE;
        }
        return switch (health.getStatus()) {
            case "starting" -> DockerHealthStatus.STARTING;
            case "healthy" -> DockerHealthStatus.HEALTHY;
            case "unhealthy" -> DockerHealthStatus.UNHEALTHY;
            default -> DockerHealthStatus.UNKNOWN;
        };
    }

    private static DockerContainerView snapshotView(
            DockerResourceRecord active, InspectContainerResponse inspection) {
        var state = inspection.getState();
        String status = state == null || state.getStatus() == null ? "unknown" : state.getStatus();
        boolean running = state != null && Boolean.TRUE.equals(state.getRunning());
        OptionalInt exitCode = !running && state != null && state.getExitCodeLong() != null
                ? OptionalInt.of(Math.toIntExact(state.getExitCodeLong()))
                : OptionalInt.empty();
        DockerHealthStatus health = healthStatus(state == null ? null : state.getHealth());
        String name = inspection.getName() == null ? "" : inspection.getName().replaceFirst("^/", "");
        String image = inspection.getConfig() == null || inspection.getConfig().getImage() == null
                ? "unknown" : inspection.getConfig().getImage();
        return new DockerContainerView(
                inspection.getId(), active.logicalName(), name, image, status, running,
                exitCode, health, inspectSnapshotPublishedPorts(inspection));
    }

    private static Optional<String> safeImageReference(InspectContainerResponse inspection) {
        Map<String, String> labels = inspection.getConfig() == null
                ? null : inspection.getConfig().getLabels();
        String reference = labels == null ? null : labels.get(LabOwnership.IMAGE_REFERENCE_LABEL);
        if (reference == null
                || reference.isBlank()
                || reference.length() > 255
                || !reference.equals(reference.strip())
                || reference.codePoints().anyMatch(Character::isISOControl)) {
            return Optional.empty();
        }
        return Optional.of(reference);
    }

    private OptionalLong inspectImageSize(InspectContainerResponse inspection) {
        String image = inspection.getConfig() == null ? null : inspection.getConfig().getImage();
        if (image == null || image.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            Long size = docker.inspectImageCmd(image).exec().getSize();
            return size == null || size < 0 ? OptionalLong.empty() : OptionalLong.of(size);
        } catch (RuntimeException failure) {
            return OptionalLong.empty();
        }
    }

    private OptionalLong inspectWritableLayerSize(DockerResourceRecord active) {
        try {
            InspectContainerResponse sized = docker.inspectContainerCmd(active.engineId().orElseThrow())
                    .withSize(true)
                    .exec();
            if (!active.hasExactLabels(
                    sized.getConfig() == null ? null : sized.getConfig().getLabels())) {
                throw ownershipMismatch();
            }
            Long size = sized.getSizeRw();
            return size == null || size < 0 ? OptionalLong.empty() : OptionalLong.of(size);
        } catch (DockerOwnershipException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            return OptionalLong.empty();
        }
    }

    private DockerContainerMetrics inspectMetrics(
            DockerResourceRecord active,
            InspectContainerResponse inspection,
            boolean running) {
        Optional<Instant> startedAt = parseStartedAt(inspection);
        if (!running) {
            return new DockerContainerMetrics(
                    OptionalDouble.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    startedAt);
        }
        var request = DockerHttpClient.Request.builder()
                .method(DockerHttpClient.Request.Method.GET)
                .path("/v1.44/containers/" + active.engineId().orElseThrow()
                        + "/stats?stream=false&one-shot=true")
                .build();
        try (DockerHttpClient.Response response = http.execute(request)) {
            if (response.getStatusCode() != 200) {
                return metricsUnavailable(startedAt);
            }
            JsonNode stats = JSON.readTree(response.getBody());
            return new DockerContainerMetrics(
                    cpuPercent(stats),
                    nonNegativeLong(stats, "memory_stats", "usage"),
                    nonNegativeLong(stats, "memory_stats", "limit"),
                    sumNetworkBytes(stats, "rx_bytes"),
                    sumNetworkBytes(stats, "tx_bytes"),
                    startedAt);
        } catch (IOException | RuntimeException failure) {
            return metricsUnavailable(startedAt);
        }
    }

    private static DockerContainerMetrics metricsUnavailable(Optional<Instant> startedAt) {
        return new DockerContainerMetrics(
                OptionalDouble.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                startedAt);
    }

    private static Optional<Instant> parseStartedAt(InspectContainerResponse inspection) {
        String value = inspection.getState() == null ? null : inspection.getState().getStartedAt();
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            Instant parsed = Instant.parse(value);
            return parsed.isBefore(Instant.EPOCH) ? Optional.empty() : Optional.of(parsed);
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static OptionalDouble cpuPercent(JsonNode stats) {
        OptionalLong currentCpu = nonNegativeLong(stats, "cpu_stats", "cpu_usage", "total_usage");
        OptionalLong priorCpu = nonNegativeLong(stats, "precpu_stats", "cpu_usage", "total_usage");
        OptionalLong currentSystem = nonNegativeLong(stats, "cpu_stats", "system_cpu_usage");
        OptionalLong priorSystem = nonNegativeLong(stats, "precpu_stats", "system_cpu_usage");
        OptionalLong online = nonNegativeLong(stats, "cpu_stats", "online_cpus");
        if (currentCpu.isEmpty() || priorCpu.isEmpty() || currentSystem.isEmpty()
                || priorSystem.isEmpty() || online.isEmpty() || online.orElseThrow() == 0) {
            return OptionalDouble.empty();
        }
        long cpuDelta = currentCpu.orElseThrow() - priorCpu.orElseThrow();
        long systemDelta = currentSystem.orElseThrow() - priorSystem.orElseThrow();
        if (cpuDelta < 0 || systemDelta <= 0) {
            return OptionalDouble.empty();
        }
        double percent = ((double) cpuDelta / (double) systemDelta) * online.orElseThrow() * 100.0;
        return Double.isFinite(percent) && percent >= 0
                ? OptionalDouble.of(percent)
                : OptionalDouble.empty();
    }

    private static OptionalLong nonNegativeLong(JsonNode root, String... fields) {
        JsonNode current = root;
        for (String field : fields) {
            current = current == null ? null : current.get(field);
        }
        if (current == null || !current.isIntegralNumber() || !current.canConvertToLong()) {
            return OptionalLong.empty();
        }
        long value = current.longValue();
        return value < 0 ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private static OptionalLong sumNetworkBytes(JsonNode stats, String field) {
        JsonNode networks = stats == null ? null : stats.get("networks");
        if (networks == null || !networks.isObject()) {
            return OptionalLong.empty();
        }
        long total = 0;
        boolean found = false;
        var values = networks.elements();
        while (values.hasNext()) {
            OptionalLong value = nonNegativeLong(values.next(), field);
            if (value.isPresent()) {
                found = true;
                try {
                    total = Math.addExact(total, value.orElseThrow());
                } catch (ArithmeticException overflow) {
                    return OptionalLong.empty();
                }
            }
        }
        return found ? OptionalLong.of(total) : OptionalLong.empty();
    }

    private void requireLogRequest(DockerResourceRecord active, int tail) {
        requireType(active, DockerResourceType.CONTAINER, DockerResourceState.ACTIVE);
        if (tail < 1 || tail > MAX_LOG_TAIL) {
            throw new IllegalArgumentException("The Docker log tail is not valid.");
        }
        inspectOwnedContainer(active);
    }

    private BoundedDockerLogCallback openLogCallback(
            DockerResourceRecord active,
            int tail,
            boolean follow,
            int maxLines,
            int maxBytes,
            Consumer<DockerLogLine> consumer) {
        BoundedDockerLogCallback callback = new BoundedDockerLogCallback(
                active.logicalName(), maxLines, maxBytes, MAX_LOG_LINE_CHARS, consumer);
        try {
            Instant now = Instant.now();
            docker.logContainerCmd(active.engineId().orElseThrow())
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTimestamps(true)
                    .withTail(tail)
                    .withSince(Math.toIntExact(now.minus(Duration.ofMinutes(15)).getEpochSecond()))
                    .withUntil(Math.toIntExact(now.plus(
                            follow ? Duration.ofMinutes(5) : Duration.ZERO).getEpochSecond()))
                    .withFollowStream(follow)
                    .exec(callback);
            return callback;
        } catch (RuntimeException failure) {
            callback.close();
            throw new DockerLogAccessException();
        }
    }

    private static List<DockerPortMapping> inspectPublishedPorts(
            InspectContainerResponse actual, DockerContainerSpec expected) {
        if (expected.publishedPorts().isEmpty()) {
            return List.of();
        }
        if (actual.getNetworkSettings() == null || actual.getNetworkSettings().getPorts() == null) {
            throw new DockerOwnershipException("Docker did not report the planned port mappings.");
        }
        Map<ExposedPort, Ports.Binding[]> bindings = actual.getNetworkSettings().getPorts().getBindings();
        List<DockerPortMapping> mappings = new ArrayList<>();
        for (DockerContainerSpec.PublishedPort expectedPort : expected.publishedPorts()) {
            Ports.Binding[] actualPorts = bindings.get(ExposedPort.tcp(expectedPort.containerPort()));
            if (actualPorts == null || actualPorts.length != 1) {
                throw new DockerOwnershipException("Docker did not report one planned host port mapping.");
            }
            Ports.Binding binding = actualPorts[0];
            int hostPort;
            try {
                hostPort = Integer.parseInt(binding.getHostPortSpec());
            } catch (NumberFormatException exception) {
                throw new DockerOwnershipException("Docker did not report a valid host port.", exception);
            }
            if (expectedPort.hostPort().isPresent()
                    && hostPort != expectedPort.hostPort().orElseThrow()) {
                throw new DockerOwnershipException("Docker reported a different fixed host port.");
            }
            mappings.add(new DockerPortMapping(
                    expectedPort.containerPort(), binding.getHostIp(), hostPort, expectedPort.protocol()));
        }
        return mappings.stream()
                .sorted(java.util.Comparator.comparingInt(DockerPortMapping::containerPort))
                .toList();
    }

    private static List<DockerPortMapping> inspectSnapshotPublishedPorts(
            InspectContainerResponse actual) {
        if (actual.getNetworkSettings() == null || actual.getNetworkSettings().getPorts() == null) {
            return List.of();
        }
        Map<ExposedPort, Ports.Binding[]> bindings = actual.getNetworkSettings().getPorts().getBindings();
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        List<DockerPortMapping> mappings = new ArrayList<>();
        for (Map.Entry<ExposedPort, Ports.Binding[]> entry : bindings.entrySet()) {
            ExposedPort exposed = entry.getKey();
            Ports.Binding[] published = entry.getValue();
            if (published == null || published.length == 0) {
                continue;
            }
            if (exposed == null
                    || !ExposedPort.tcp(exposed.getPort()).equals(exposed)
                    || published.length != 1) {
                throw new DockerOwnershipException("Docker reported an unsupported host port mapping.");
            }
            Ports.Binding binding = published[0];
            if (binding == null) {
                throw new DockerOwnershipException("Docker reported an empty host port mapping.");
            }
            int hostPort;
            try {
                hostPort = Integer.parseInt(binding.getHostPortSpec());
            } catch (NumberFormatException exception) {
                throw new DockerOwnershipException("Docker did not report a valid host port.", exception);
            }
            mappings.add(new DockerPortMapping(
                    exposed.getPort(), binding.getHostIp(), hostPort, "tcp"));
        }
        return mappings.stream()
                .sorted(java.util.Comparator.comparingInt(DockerPortMapping::containerPort))
                .toList();
    }

    private static boolean isPortCollision(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("port is already allocated")
                    || normalized.contains("address already in use")
                    || normalized.contains("failed to bind host port")
                    || normalized.contains("ports are not available")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasExactContainerLabels(String id, DockerResourceRecord expected) {
        try {
            InspectContainerResponse actual = docker.inspectContainerCmd(id).exec();
            return expected.hasExactLabels(actual.getConfig() == null ? null : actual.getConfig().getLabels());
        } catch (NotFoundException exception) {
            return false;
        }
    }

    private boolean hasExactContainerIdentity(String id, DockerResourceRecord expected) {
        try {
            InspectContainerResponse actual = docker.inspectContainerCmd(id).exec();
            String actualName = actual.getName() == null ? "" : actual.getName().replaceFirst("^/", "");
            return engineName(expected).equals(actualName)
                    && expected.hasExactLabels(
                            actual.getConfig() == null ? null : actual.getConfig().getLabels());
        } catch (NotFoundException exception) {
            return false;
        }
    }

    private boolean containerIsAbsent(String id) {
        try {
            docker.inspectContainerCmd(id).exec();
            return false;
        } catch (NotFoundException exception) {
            return true;
        }
    }

    private boolean networkIsAbsent(String id) {
        try {
            docker.inspectNetworkCmd().withNetworkId(id).exec();
            return false;
        } catch (NotFoundException exception) {
            return true;
        }
    }

    private boolean hasExactNetworkLabels(String id, DockerResourceRecord expected) {
        try {
            return expected.hasExactLabels(docker.inspectNetworkCmd().withNetworkId(id).exec().getLabels());
        } catch (NotFoundException exception) {
            return false;
        }
    }

    private boolean hasExactNetworkIdentity(String id, DockerResourceRecord expected) {
        try {
            var actual = docker.inspectNetworkCmd().withNetworkId(id).exec();
            return engineName(expected).equals(actual.getName())
                    && expected.hasExactLabels(actual.getLabels());
        } catch (NotFoundException exception) {
            return false;
        }
    }

    private boolean hasExactVolumeLabels(String id, DockerResourceRecord expected) {
        try {
            return expected.hasExactLabels(docker.inspectVolumeCmd(id).exec().getLabels());
        } catch (NotFoundException exception) {
            return false;
        }
    }

    private static void requireDispatched(DockerResourceRecord resource) {
        if (resource == null || resource.state() != DockerResourceState.DISPATCHED
                || resource.engineId().isPresent()) {
            throw new IllegalArgumentException("A dispatched Docker resource record is required.");
        }
    }

    private static void requireType(
            DockerResourceRecord resource, DockerResourceType type, DockerResourceState state) {
        if (resource == null || resource.type() != type || resource.state() != state) {
            throw new IllegalArgumentException("The Docker resource record has the wrong type or state.");
        }
    }

    private static String engineName(DockerResourceRecord resource) {
        String lab = resource.ownership().labId().toLowerCase(java.util.Locale.ROOT)
                .replace('_', '-').substring(0, Math.min(12, resource.ownership().labId().length()));
        String logical = resource.logicalName().substring(0, Math.min(12, resource.logicalName().length()));
        return "labdeck-" + resource.type().labelValue().charAt(0) + "-" + lab + "-" + logical
                + "-" + resource.ownershipToken().substring(0, 12);
    }

    private static Collection<String> labelFilters(Map<String, String> labels) {
        return labels.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();
    }

    private static List<String> environment(Map<String, String> values) {
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();
    }

    private static boolean hasExactName(String[] names, String expected) {
        return names != null && java.util.Arrays.stream(names)
                .map(name -> name.replaceFirst("^/", ""))
                .anyMatch(expected::equals);
    }

    private static void requireImageReference(String reference) {
        if (reference == null || reference.isBlank() || reference.length() > 255
                || !reference.equals(reference.strip())) {
            throw new IllegalArgumentException("The image reference is not valid.");
        }
    }

    private static void requireTimeout(Duration timeout, Duration minimum, Duration maximum) {
        if (timeout == null || timeout.compareTo(minimum) < 0 || timeout.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("The Docker operation timeout is not valid.");
        }
    }

    private static void requireCreatedId(String id) {
        if (id == null || id.isBlank() || id.length() > 255) {
            throw new IllegalStateException("Docker did not return a valid resource ID.");
        }
    }

    private String inspectVolumeIdentity(String name) {
        if (name == null || !VOLUME_NAME.matcher(name).matches()) {
            throw new DockerOwnershipException("The Docker volume name is not safe to inspect.");
        }
        var request = DockerHttpClient.Request.builder()
                .method(DockerHttpClient.Request.Method.GET)
                .path("/v1.44/volumes/" + name)
                .build();
        try (DockerHttpClient.Response response = http.execute(request)) {
            if (response.getStatusCode() != 200) {
                throw new DockerOwnershipException(
                        "The Docker volume identity could not be inspected.");
            }
            JsonNode document = JSON.readTree(response.getBody());
            JsonNode createdAt = document == null ? null : document.get("CreatedAt");
            String identity = createdAt == null || !createdAt.isTextual() ? null : createdAt.textValue();
            if (identity == null || identity.isBlank() || identity.length() > 255
                    || !identity.equals(identity.strip())
                    || identity.codePoints().anyMatch(Character::isISOControl)) {
                throw new DockerOwnershipException(
                        "Docker did not return a stable volume creation identity.");
            }
            return identity;
        } catch (DockerOwnershipException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new DockerOwnershipException(
                    "The Docker volume identity could not be inspected safely.", exception);
        }
    }

    private static DockerHttpClient requireHttpClient(DockerClient docker) {
        if (docker instanceof DockerClientImpl client) {
            return client.getHttpClient();
        }
        throw new IllegalArgumentException("A docker-java client with its HTTP transport is required.");
    }

    private static RuntimeException propagatePullFailure(Throwable cause) {
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause instanceof DockerOperationCancelledException cancellation) {
            return cancellation;
        }
        if (cause instanceof RuntimeException runtimeException) {
            return classifyPullFailure(runtimeException);
        }
        return new DockerImagePullException(DockerImagePullException.Reason.FAILED);
    }

    private static RuntimeException classifyPullFailure(RuntimeException failure) {
        if (failure instanceof DockerOperationCancelledException
                || failure instanceof DockerImagePullException
                || failure instanceof DockerStorageFullException) {
            return failure;
        }
        if (isStorageFull(failure)) {
            return new DockerStorageFullException();
        }
        return new DockerImagePullException(DockerImagePullException.Reason.FAILED);
    }

    private static RuntimeException classifyStorageFailure(RuntimeException failure) {
        return isStorageFull(failure) ? new DockerStorageFullException() : failure;
    }

    private static boolean isStorageFull(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("no space left on device") || normalized.contains("enospc")) {
                return true;
            }
        }
        return false;
    }

    private static DockerCreateWithoutOwnedResourceException createWithoutOwnedResource(
            RuntimeException cause) {
        return new DockerCreateWithoutOwnedResourceException(
                "Docker did not create a LabDeck-owned resource.", cause);
    }

    static BindOptions nonRecursiveWorkspaceBindOptions() {
        NonRecursiveBindOptions options = new NonRecursiveBindOptions();
        options.withPropagation(BindPropagation.R_PRIVATE);
        return options;
    }

    private static DockerOwnershipException ownershipMismatch() {
        return new DockerOwnershipException("The Docker resource does not match its ownership journal.");
    }

    private static DockerOwnershipException staleResourceId(NotFoundException cause) {
        DockerOwnershipException exception = new DockerOwnershipException(
                "The Docker resource ID in the ownership journal is stale.");
        exception.initCause(cause);
        return exception;
    }

    private static final class NonRecursiveBindOptions extends BindOptions {

        @JsonProperty("NonRecursive")
        public boolean isNonRecursive() {
            return true;
        }
    }
}
