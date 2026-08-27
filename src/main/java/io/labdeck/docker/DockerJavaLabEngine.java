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
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DockerJavaLabEngine implements DockerEnginePort {

    private static final Pattern VOLUME_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}");
    private static final ObjectMapper JSON = new ObjectMapper();

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
        docker.pingCmd().exec();
    }

    @Override
    public Optional<DockerImageMetadata> inspectImage(String reference) {
        requireImageReference(reference);
        try {
            InspectImageResponse image = docker.inspectImageCmd(reference).exec();
            ContainerConfig config = image.getConfig();
            Map<String, ?> declaredVolumes = config == null ? null : config.getVolumes();
            Set<String> targets = declaredVolumes == null ? Set.of() : Set.copyOf(declaredVolumes.keySet());
            return Optional.of(new DockerImageMetadata(
                    image.getId(), image.getSize() == null ? 0 : image.getSize(), targets));
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
                    throw new IllegalStateException("The confirmed public image pull timed out.");
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
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The confirmed public image pull was interrupted.", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("The confirmed public image pull could not close safely.", exception);
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
        String id = docker.createNetworkCmd()
                .withName(engineName(dispatched))
                .withDriver("bridge")
                .withInternal(true)
                .withAttachable(false)
                .withCheckDuplicate(false)
                .withLabels(dispatched.labels())
                .exec()
                .getId();
        requireCreatedId(id);
        var created = docker.inspectNetworkCmd().withNetworkId(id).exec();
        if (!dispatched.hasExactLabels(created.getLabels())
                || !"bridge".equals(created.getDriver())
                || !Boolean.TRUE.equals(created.getInternal())
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
        String id = docker.createVolumeCmd()
                .withName(engineName(dispatched))
                .withDriver("local")
                .withLabels(dispatched.labels())
                .exec()
                .getName();
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

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withNetworkMode(specification.networkId())
                .withMounts(mounts)
                .withAutoRemove(false)
                .withPrivileged(false)
                .withPublishAllPorts(false)
                .withRestartPolicy(RestartPolicy.noRestart());
        CreateContainerCmd command = docker.createContainerCmd(specification.image())
                .withAuthConfig(new AuthConfig())
                .withName(engineName(dispatched))
                .withLabels(dispatched.labels())
                .withAliases(dispatched.logicalName())
                .withWorkingDir(specification.workingDirectory())
                .withEnv(environment(specification.environment()))
                .withHostConfig(hostConfig);
        if (!specification.command().isEmpty()) {
            command.withCmd(specification.command());
        }
        String id = command.exec().getId();
        requireCreatedId(id);
        DockerCreatedResource result = DockerCreatedResource.withImmutableId(id);
        InspectContainerResponse created = inspectOwnedContainer(dispatched.activate(result, dispatched.updatedAt()));
        requireContainerShape(created, specification);
        return result;
    }

    @Override
    public DockerContainerView inspectContainer(DockerResourceRecord active) {
        requireType(active, DockerResourceType.CONTAINER, DockerResourceState.ACTIVE);
        InspectContainerResponse inspection = inspectOwnedContainer(active);
        var state = inspection.getState();
        String status = state == null || state.getStatus() == null ? "unknown" : state.getStatus();
        boolean running = state != null && Boolean.TRUE.equals(state.getRunning());
        String name = inspection.getName() == null ? "" : inspection.getName().replaceFirst("^/", "");
        String image = inspection.getConfig() == null || inspection.getConfig().getImage() == null
                ? "unknown" : inspection.getConfig().getImage();
        return new DockerContainerView(inspection.getId(), name, image, status, running);
    }

    @Override
    public void startContainer(DockerResourceRecord active) {
        requireType(active, DockerResourceType.CONTAINER, DockerResourceState.ACTIVE);
        inspectOwnedContainer(active);
        try {
            docker.startContainerCmd(active.engineId().orElseThrow()).exec();
        } catch (RuntimeException ambiguousFailure) {
            InspectContainerResponse reconciled = inspectOwnedContainer(active);
            if (reconciled.getState() != null
                    && Boolean.TRUE.equals(reconciled.getState().getRunning())) {
                return;
            }
            throw ambiguousFailure;
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
                || actual.getHostConfig() == null
                || !expected.networkId().equals(actual.getHostConfig().getNetworkMode())) {
            throw new DockerOwnershipException(
                    "Docker created a container with a different image or network than planned.");
        }
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
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("The confirmed public image pull failed.", cause);
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
