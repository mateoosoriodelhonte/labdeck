package io.labdeck.docker;

import io.labdeck.lab.LabFailureCode;
import io.labdeck.lab.LabRecord;
import io.labdeck.lab.LabRepository;
import io.labdeck.lab.LabRuntimeFailure;
import io.labdeck.lab.LabState;
import io.labdeck.manifest.ApprovedWorkspacePath;
import io.labdeck.manifest.LabManifest.BuildSource;
import io.labdeck.manifest.LabManifest.ImageSource;
import io.labdeck.manifest.ManifestPlan;
import io.labdeck.manifest.ManifestPlan.ServicePlan;
import io.labdeck.manifest.ProjectPathPolicy;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DockerLabLifecycle implements AutoCloseable {

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PULL_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration OBSERVATION_TIMEOUT = Duration.ofSeconds(5);

    private final DockerEnginePort engine;
    private final DockerResourceJournal journal;
    private final LabRepository labs;
    private final ProjectPathPolicy paths;
    private final Clock clock;
    private final Supplier<String> tokenSupplier;
    private final DockerReadinessWaiter readiness;
    private final DockerRuntimeMonitorPort runtimeMonitor;
    private final ConcurrentHashMap<String, ReentrantLock> labLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActiveStart> activeStarts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActiveTest> activeTests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> runningManifestHashes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Integer>> stopRequests =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<TrackedLogSubscription>> logSubscriptions =
            new ConcurrentHashMap<>();
    private final ExecutorService observationExecutor = newObservationExecutor();

    @Autowired
    public DockerLabLifecycle(
            DockerEnginePort engine, DockerResourceJournal journal, LabRepository labs) {
        this(
                engine,
                journal,
                labs,
                new ProjectPathPolicy(),
                Clock.systemUTC(),
                secureTokenSupplier(),
                new DockerReadinessWaiter(),
                new DockerRuntimeMonitor());
    }

    DockerLabLifecycle(
            DockerEnginePort engine,
            DockerResourceJournal journal,
            LabRepository labs,
            ProjectPathPolicy paths,
            Clock clock,
            Supplier<String> tokenSupplier) {
        this(
                engine,
                journal,
                labs,
                paths,
                clock,
                tokenSupplier,
                new DockerReadinessWaiter(),
                new DockerRuntimeMonitor());
    }

    DockerLabLifecycle(
            DockerEnginePort engine,
            DockerResourceJournal journal,
            LabRepository labs,
            ProjectPathPolicy paths,
            Clock clock,
            Supplier<String> tokenSupplier,
            DockerReadinessWaiter readiness) {
        this(
                engine,
                journal,
                labs,
                paths,
                clock,
                tokenSupplier,
                readiness,
                new DockerRuntimeMonitor());
    }

    DockerLabLifecycle(
            DockerEnginePort engine,
            DockerResourceJournal journal,
            LabRepository labs,
            ProjectPathPolicy paths,
            Clock clock,
            Supplier<String> tokenSupplier,
            DockerReadinessWaiter readiness,
            DockerRuntimeMonitorPort runtimeMonitor) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.labs = Objects.requireNonNull(labs, "labs");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.runtimeMonitor = Objects.requireNonNull(runtimeMonitor, "runtimeMonitor");
    }

    public List<DockerImagePlan> inspectRequiredImages(ManifestPlan plan) {
        requirePlan(plan);
        rejectBuildServices(plan);
        engine.verifyAvailable();
        return plan.images().stream()
                .map(reference -> new DockerImagePlan(reference, engine.inspectImage(reference)))
                .toList();
    }

    public void pullConfirmedImages(
            ManifestPlan plan, Collection<String> confirmedReferences, CancellationToken cancellation) {
        requirePlan(plan);
        rejectBuildServices(plan);
        Objects.requireNonNull(confirmedReferences, "confirmedReferences");
        cancellation = cancellation == null ? CancellationToken.NONE : cancellation;
        Set<String> required = Set.copyOf(plan.images());
        List<String> confirmed = confirmedReferences.stream().distinct().sorted().toList();
        if (confirmed.isEmpty() || !required.containsAll(confirmed)) {
            throw new IllegalArgumentException("Only required manifest images can be confirmed for download.");
        }
        engine.verifyAvailable();
        for (String reference : confirmed) {
            cancellation.throwIfCancellationRequested();
            if (engine.inspectImage(reference).isEmpty()) {
                engine.pullPublicImageAfterConfirmation(reference, PULL_TIMEOUT, cancellation);
            }
            cancellation.throwIfCancellationRequested();
            if (engine.inspectImage(reference).isEmpty()) {
                throw new IllegalStateException("Docker did not make the confirmed image available locally.");
            }
        }
    }

    DockerStartResult start(
            LabRecord requestedLab, ManifestPlan plan, CancellationToken cancellation) {
        Objects.requireNonNull(requestedLab, "requestedLab");
        return start(
                requestedLab,
                paths.resolveWorkspace(requestedLab.workspace()),
                plan,
                cancellation);
    }

    public DockerStartResult start(
            LabRecord requestedLab,
            ApprovedWorkspacePath approvedWorkspace,
            ManifestPlan plan,
            CancellationToken cancellation) {
        Objects.requireNonNull(requestedLab, "requestedLab");
        Objects.requireNonNull(approvedWorkspace, "approvedWorkspace");
        requirePlan(plan);
        rejectBuildServices(plan);
        if (requestedLab.manifestVersion() != plan.schemaVersion()
                || !requestedLab.name().equals(plan.name())) {
            throw new IllegalArgumentException("The lab record and manifest plan do not match.");
        }
        cancellation = cancellation == null ? CancellationToken.NONE : cancellation;
        Map<String, ServiceDockerPolicy> policies = compileDockerPolicies(plan);
        ActiveStart operation = new ActiveStart(requestedLab.revision(), cancellation);
        if (activeStarts.putIfAbsent(requestedLab.id(), operation) != null) {
            throw new IllegalStateException("Another start operation is already active for this lab.");
        }
        if (hasStopRequest(requestedLab.id(), requestedLab.revision())) {
            operation.cancel();
        }
        ReentrantLock lock = lockFor(requestedLab.id());
        lock.lock();
        try {
            operation.throwIfCancellationRequested();
            LabRecord current = labs.findById(requestedLab.id())
                    .orElseThrow(() -> new IllegalStateException("The lab does not exist."));
            if (current.revision() != requestedLab.revision()
                    || !current.projectId().equals(requestedLab.projectId())) {
                throw new IllegalStateException("The lab changed before startup began.");
            }
            if (!Set.of(LabState.IMPORTED, LabState.STOPPED, LabState.FAILED).contains(current.state())) {
                throw new IllegalStateException("The lab cannot start from its current state.");
            }

            if (!approvedWorkspace.path().equals(current.workspace())) {
                throw new IllegalStateException("The reviewed workspace does not match the selected lab.");
            }
            approvedWorkspace.verifyUnchanged();
            preflightWorkspaceTargets(plan);
            engine.verifyAvailable();
            engine.verifyResourceLimitsSupported();
            if (policies.values().stream().anyMatch(policy -> !policy.ports().isEmpty())) {
                engine.verifyLocalPortPublishingSupported();
            }
            Map<String, DockerImageMetadata> images = resolveImages(plan);
            operation.throwIfCancellationRequested();

            Instant transitionTime = now();
            LabRecord starting = current.transitionTo(LabState.STARTING, transitionTime);
            operation.advanceRevision(starting.revision());
            if (!labs.compareAndSetState(
                    current.id(), current.revision(), current.state(), LabState.STARTING, transitionTime)) {
                throw new IllegalStateException("Another lab operation won the startup race.");
            }

            LabOwnership ownership = new LabOwnership(starting.id(), starting.projectId());
            LabRecord committedRun = null;
            try {
                cleanupEphemeralResources(ownership, operation);
                operation.throwIfCancellationRequested();
                DockerResourceRecord network = createTracked(
                        ownership, DockerResourceType.NETWORK, "lab-network", engine::createNetwork);

                Map<String, DockerResourceRecord> volumes = new TreeMap<>();
                for (String logicalName : plan.volumes()) {
                    operation.throwIfCancellationRequested();
                    volumes.put(logicalName, ensurePersistentVolume(ownership, logicalName));
                }

                List<PlannedContainer> containers = new ArrayList<>();
                for (ServicePlan service : plan.services()) {
                    operation.throwIfCancellationRequested();
                    DockerImageMetadata image = images.get(service.id());
                    ServiceDockerPolicy policy = policies.get(service.id());
                    List<DockerContainerSpec.NamedMount> mounts = service.definition().volumes().stream()
                            .map(volume -> {
                                DockerResourceRecord resource = volumes.get(volume.name());
                                return new DockerContainerSpec.NamedMount(
                                        resource.engineId().orElseThrow(), volume.target(), volume.readOnly());
                            })
                            .toList();
                    DockerContainerSpec specification = new DockerContainerSpec(
                            image.id(),
                            ((ImageSource) service.definition().source()).reference(),
                            service.definition().workingDirectory(),
                            service.definition().command(),
                            service.definition().environment(),
                            approvedWorkspace,
                            plan.workspaceMount(),
                            network.engineId().orElseThrow(),
                            mounts,
                            policy.ports(),
                            policy.resources(),
                            policy.healthProbe(),
                            image.healthCheckConfigured());
                    DockerResourceRecord container = createTracked(
                            ownership,
                            DockerResourceType.CONTAINER,
                            service.id(),
                            dispatched -> engine.createContainer(dispatched, specification));
                    containers.add(new PlannedContainer(container, specification));
                }

                for (PlannedContainer container : containers) {
                    operation.throwIfCancellationRequested();
                    engine.startContainer(container.resource(), container.specification());
                }
                List<DockerReadinessWaiter.ServiceProbe> probes = containers.stream()
                        .map(container -> new DockerReadinessWaiter.ServiceProbe(
                                container.resource().logicalName(),
                                container.specification().healthCheckRequired(),
                                () -> engine.inspectContainer(
                                        container.resource(), container.specification())))
                        .toList();
                List<DockerContainerView> views = readiness.await(
                        probes, readinessTimeout(containers), operation);
                operation.throwIfCancellationRequested();

                Instant runningAt = now();
                LabRecord running = starting.transitionTo(LabState.RUNNING, runningAt);
                if (!operation.commitRunning(() -> labs.compareAndSetState(
                        starting.id(), starting.revision(), LabState.STARTING, LabState.RUNNING, runningAt))) {
                    throw new IllegalStateException("The ready lab state could not be stored.");
                }
                committedRun = running;
                runningManifestHashes.put(running.id(), plan.manifestSha256());
                runtimeMonitor.watch(
                        running.id(),
                        running.revision(),
                        probes,
                        failure -> handleRuntimeFailure(running, ownership, failure));
                return new DockerStartResult(
                        running,
                        network.engineId().orElseThrow(),
                        volumes.values().stream().map(value -> value.engineId().orElseThrow()).toList(),
                        views);
            } catch (DockerOperationCancelledException cancellationFailure) {
                if (committedRun == null) {
                    finishCancelledStart(starting, ownership, cancellationFailure);
                } else {
                    finishCancelledCommittedRun(committedRun, ownership, cancellationFailure);
                }
                throw cancellationFailure;
            } catch (RuntimeException failure) {
                if (committedRun == null) {
                    finishFailedStart(starting, ownership, failure);
                } else {
                    finishFailedCommittedRun(committedRun, ownership, failure);
                }
                throw failure;
            }
        } finally {
            activeStarts.remove(requestedLab.id(), operation);
            unlock(lock);
        }
    }

    public LabRecord stop(String labId) {
        return stop(labId, null);
    }

    public LabRecord stop(String labId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("The expected lab revision is not valid.");
        }
        return stop(labId, Long.valueOf(expectedRevision));
    }

    private LabRecord stop(String labId, Long expectedRevision) {
        if (labId == null || labId.isBlank()) {
            throw new IllegalArgumentException("The lab ID is required.");
        }
        if (expectedRevision != null) {
            LabRecord observed = labs.findById(labId)
                    .orElseThrow(() -> new IllegalStateException("The lab does not exist."));
            if (observed.revision() != expectedRevision.longValue()) {
                throw new IllegalStateException("The lab changed before the stop operation began.");
            }
        }
        registerStopRequest(labId, expectedRevision);
        try {
            ActiveStart active = activeStarts.get(labId);
            boolean cancelledMatchingStart = active != null && active.matches(expectedRevision);
            if (cancelledMatchingStart) {
                active.cancel();
            }
            if (expectedRevision == null) {
                runtimeMonitor.cancel(labId);
            }
            ReentrantLock lock = lockFor(labId);
            lock.lock();
            try {
                LabRecord current = labs.findById(labId)
                        .orElseThrow(() -> new IllegalStateException("The lab does not exist."));
                if (expectedRevision != null && current.revision() != expectedRevision.longValue()) {
                    if (cancelledMatchingStart && current.state() == LabState.STOPPED) {
                        return current;
                    }
                    throw new IllegalStateException("The lab changed before the stop operation began.");
                }
                if (expectedRevision != null) {
                    runtimeMonitor.cancel(labId);
                }
                cancelActiveTest(labId, DockerTestCancelCause.LAB_STOPPED);
                cancelLogSubscriptions(labId);
                LabState cleanupState = current.state();
                LabRecord stopping = current;
                if (Set.of(LabState.STARTING, LabState.RUNNING, LabState.FAILED).contains(current.state())) {
                    runningManifestHashes.remove(labId);
                    Instant transitionTime = now();
                    stopping = current.transitionTo(LabState.STOPPING, transitionTime);
                    if (!labs.compareAndSetState(
                            current.id(), current.revision(), current.state(), LabState.STOPPING, transitionTime)) {
                        throw new IllegalStateException("Another lab operation won the stop race.");
                    }
                    cleanupState = LabState.STOPPING;
                } else if (current.state() == LabState.IMPORTED) {
                    runningManifestHashes.remove(labId);
                    Instant transitionTime = now();
                    LabRecord stopped = current.transitionTo(LabState.STOPPED, transitionTime);
                    if (!labs.compareAndSetState(
                            current.id(), current.revision(), LabState.IMPORTED, LabState.STOPPED, transitionTime)) {
                        throw new IllegalStateException("Another lab operation won the stop race.");
                    }
                    return stopped;
                } else if (current.state() == LabState.STOPPED) {
                    runningManifestHashes.remove(labId);
                    return current;
                } else {
                    throw new IllegalStateException("The lab cannot stop from its current state.");
                }

                LabOwnership ownership = new LabOwnership(stopping.id(), stopping.projectId());
                try {
                    cleanupEphemeralResources(ownership, CancellationToken.NONE);
                    Instant stoppedAt = now();
                    LabRecord stopped = stopping.transitionTo(LabState.STOPPED, stoppedAt);
                    if (!labs.compareAndSetState(
                            stopping.id(), stopping.revision(), cleanupState, LabState.STOPPED, stoppedAt)) {
                        throw new IllegalStateException("The stopped lab state could not be stored.");
                    }
                    return stopped;
                } catch (RuntimeException failure) {
                    if (cleanupState == LabState.STOPPING) {
                        storeFailure(
                                stopping,
                                LabFailureCode.CLEANUP_INCOMPLETE,
                                Optional.empty(),
                                true);
                    }
                    throw failure;
                }
            } finally {
                unlock(lock);
            }
        } finally {
            clearStopRequest(labId, expectedRevision);
        }
    }

    public List<DockerContainerView> inspectServices(String labId) {
        return inspectServiceSnapshot(labId).services();
    }

    public DockerServiceSnapshot inspectServiceSnapshot(String labId) {
        if (labId == null || !labId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("The lab ID is not valid.");
        }
        ReentrantLock lock = lockFor(labId);
        lock.lock();
        try {
            LabRecord current = labs.findById(labId)
                    .orElseThrow(() -> new IllegalStateException("The lab does not exist."));
            if (!Set.of(LabState.RUNNING, LabState.FAILED).contains(current.state())) {
                return new DockerServiceSnapshot(current, List.of());
            }
            LabOwnership ownership = new LabOwnership(current.id(), current.projectId());
            List<DockerResourceRecord> containers = journal.findOpenByLab(ownership).stream()
                    .filter(resource -> resource.type() == DockerResourceType.CONTAINER)
                    .filter(resource -> resource.state() == DockerResourceState.ACTIVE)
                    .sorted(java.util.Comparator.comparing(DockerResourceRecord::logicalName))
                    .toList();
            if (containers.isEmpty()) {
                return new DockerServiceSnapshot(current, List.of());
            }
            engine.verifyAvailable();
            return new DockerServiceSnapshot(
                    current,
                    containers.stream().map(engine::inspectContainerSnapshot).toList());
        } finally {
            unlock(lock);
        }
    }

    public DockerObservabilitySnapshot inspectObservabilitySnapshot(String labId) {
        if (labId == null || !labId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("The lab ID is not valid.");
        }
        ReentrantLock lock = lockFor(labId);
        LabRecord observedLab;
        List<DockerResourceRecord> active;
        lock.lock();
        try {
            observedLab = labs.findById(labId)
                    .orElseThrow(() -> new IllegalStateException("The lab does not exist."));
            LabOwnership ownership = new LabOwnership(observedLab.id(), observedLab.projectId());
            active = journal.findOpenByLab(ownership).stream()
                    .filter(resource -> resource.state() == DockerResourceState.ACTIVE)
                    .toList();
        } finally {
            unlock(lock);
        }
        ObservationResult observation;
        if (active.isEmpty()) {
            observation = new ObservationResult(List.of(), List.of(), false);
        } else {
            try {
                observation = inspectWithinDeadline(active);
            } catch (RuntimeException failure) {
                throwIfObservationIsStale(lock, labId, observedLab);
                throw failure;
            }
        }
        lock.lock();
        try {
            LabRecord current = labs.findById(labId)
                    .orElseThrow(() -> new IllegalStateException("The lab does not exist."));
            requireCurrentObservation(current, observedLab);
            return new DockerObservabilitySnapshot(
                    current,
                    observation.services(),
                    observation.volumes(),
                    observation.networkPresent());
        } finally {
            unlock(lock);
        }
    }

    private ObservationResult inspectWithinDeadline(List<DockerResourceRecord> active) {
        Future<ObservationResult> future;
        try {
            future = observationExecutor.submit(() -> inspectActiveResources(active));
        } catch (RejectedExecutionException failure) {
            throw new DockerObservationTimeoutException();
        }
        try {
            return future.get(OBSERVATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            future.cancel(true);
            throw new DockerObservationTimeoutException();
        } catch (InterruptedException failure) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DockerObservationTimeoutException();
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Docker observation failed safely.");
        }
    }

    private ObservationResult inspectActiveResources(List<DockerResourceRecord> active) {
        engine.verifyAvailable();
        Map<String, String> ownedVolumes = active.stream()
                .filter(resource -> resource.type() == DockerResourceType.VOLUME)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        resource -> resource.engineId().orElseThrow(),
                        DockerResourceRecord::logicalName));
        List<DockerServiceObservation> services = active.stream()
                .filter(resource -> resource.type() == DockerResourceType.CONTAINER)
                .sorted(java.util.Comparator.comparing(DockerResourceRecord::logicalName))
                .map(resource -> engine.inspectContainerObservation(resource, ownedVolumes))
                .toList();
        List<DockerVolumeObservation> volumes = active.stream()
                .filter(resource -> resource.type() == DockerResourceType.VOLUME)
                .sorted(java.util.Comparator.comparing(DockerResourceRecord::logicalName))
                .map(engine::inspectVolumeObservation)
                .toList();
        List<DockerResourceRecord> networks = active.stream()
                .filter(resource -> resource.type() == DockerResourceType.NETWORK)
                .toList();
        if (networks.size() > 1) {
            throw new DockerOwnershipException("The lab has more than one active Docker network.");
        }
        networks.forEach(engine::verifyNetwork);
        return new ObservationResult(services, volumes, !networks.isEmpty());
    }

    private void throwIfObservationIsStale(
            ReentrantLock lock, String labId, LabRecord observedLab) {
        lock.lock();
        try {
            LabRecord current = labs.findById(labId)
                    .orElseThrow(() -> new IllegalStateException("The lab does not exist."));
            requireCurrentObservation(current, observedLab);
        } finally {
            unlock(lock);
        }
    }

    private static void requireCurrentObservation(LabRecord current, LabRecord observed) {
        if (current.revision() != observed.revision() || current.state() != observed.state()) {
            throw new IllegalStateException(
                    "The lab changed during Docker observation. Refresh and retry.");
        }
    }

    public DockerLogBatch readLogs(String labId, String service, int tail) {
        ActiveLogTarget target = resolveActiveContainer(labId, service);
        engine.verifyAvailable();
        return engine.readContainerLogs(target.container(), tail);
    }

    public DockerLogSubscription followLogs(
            String labId, String service, int tail, Consumer<DockerLogLine> consumer) {
        ActiveLogTarget target = resolveActiveContainer(labId, service);
        engine.verifyAvailable();
        DockerLogSubscription delegate = engine.followContainerLogs(
                target.container(), tail, consumer);
        TrackedLogSubscription tracked = new TrackedLogSubscription(labId, delegate);
        ReentrantLock lock = lockFor(labId);
        lock.lock();
        try {
            LabRecord current = labs.findById(labId)
                    .orElseThrow(() -> new IllegalStateException("The lab does not exist."));
            if (current.revision() != target.lab().revision()
                    || current.state() != target.lab().state()) {
                tracked.close();
                throw new IllegalStateException(
                        "The lab changed before the log stream began. Refresh and retry.");
            }
            logSubscriptions.computeIfAbsent(
                    labId, ignored -> ConcurrentHashMap.newKeySet()).add(tracked);
            return tracked;
        } finally {
            unlock(lock);
        }
    }

    public DockerLabTestResult executeTest(LabRecord requestedLab, ManifestPlan plan) {
        return executeTest(requestedLab, plan, CancellationToken.NONE);
    }

    public void validateTestStart(LabRecord requestedLab, ManifestPlan plan) {
        Objects.requireNonNull(requestedLab, "requestedLab");
        requirePlan(plan);
        if (plan.tests().isEmpty()) {
            throw new IllegalArgumentException("The manifest does not define an assignment test.");
        }
        ReentrantLock lock = lockFor(requestedLab.id());
        lock.lock();
        try {
            LabRecord current = labs.findById(requestedLab.id())
                    .orElseThrow(() -> new DockerTestStartException(
                            DockerTestStartException.Reason.LAB_CHANGED));
            if (current.revision() != requestedLab.revision()
                    || !current.projectId().equals(requestedLab.projectId())) {
                throw new DockerTestStartException(DockerTestStartException.Reason.LAB_CHANGED);
            }
            if (current.state() != LabState.RUNNING
                    || hasStopRequest(current.id(), current.revision())) {
                throw new DockerTestStartException(DockerTestStartException.Reason.LAB_NOT_RUNNING);
            }
            if (!plan.manifestSha256().equals(runningManifestHashes.get(current.id()))) {
                throw new DockerTestStartException(DockerTestStartException.Reason.RESTART_REQUIRED);
            }
        } finally {
            unlock(lock);
        }
    }

    public DockerLabTestResult executeTest(
            LabRecord requestedLab, ManifestPlan plan, CancellationToken externalCancellation) {
        Objects.requireNonNull(requestedLab, "requestedLab");
        externalCancellation = externalCancellation == null
                ? CancellationToken.NONE : externalCancellation;
        requirePlan(plan);
        var test = plan.tests().orElseThrow(() -> new IllegalArgumentException(
                "The manifest does not define an assignment test."));
        ServicePlan service = plan.services().stream()
                .filter(candidate -> candidate.id().equals(test.service()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "The manifest test service is not defined."));
        ActiveTest operation = new ActiveTest(externalCancellation);
        if (activeTests.putIfAbsent(requestedLab.id(), operation) != null) {
            throw new DockerTestStartException(DockerTestStartException.Reason.ALREADY_RUNNING);
        }

        DockerResourceRecord container;
        ReentrantLock lock = lockFor(requestedLab.id());
        lock.lock();
        try {
            LabRecord current = labs.findById(requestedLab.id())
                    .orElseThrow(() -> new IllegalStateException("The lab does not exist."));
            if (current.revision() != requestedLab.revision()
                    || !current.projectId().equals(requestedLab.projectId())) {
                throw new DockerTestStartException(DockerTestStartException.Reason.LAB_CHANGED);
            }
            if (current.state() != LabState.RUNNING
                    || hasStopRequest(current.id(), current.revision())) {
                throw new DockerTestStartException(DockerTestStartException.Reason.LAB_NOT_RUNNING);
            }
            if (!plan.manifestSha256().equals(runningManifestHashes.get(current.id()))) {
                throw new DockerTestStartException(DockerTestStartException.Reason.RESTART_REQUIRED);
            }
            LabOwnership ownership = new LabOwnership(current.id(), current.projectId());
            container = journal.findOpen(
                            ownership, DockerResourceType.CONTAINER, test.service())
                    .filter(resource -> resource.state() == DockerResourceState.ACTIVE)
                    .orElseThrow(DockerActiveServiceNotFoundException::new);
        } catch (RuntimeException failure) {
            activeTests.remove(requestedLab.id(), operation);
            throw failure;
        } finally {
            unlock(lock);
        }

        try {
            DockerTestExecutionResult execution;
            try {
                engine.verifyAvailable();
                execution = engine.executeContainerTest(
                        container,
                        test.command(),
                        service.definition().workingDirectory(),
                        test.timeout(),
                        operation);
            } catch (DockerOperationCancelledException failure) {
                execution = emptyTestResult(DockerTestExecutionState.CANCELLED);
            } catch (DockerOwnershipException failure) {
                execution = emptyTestResult(DockerTestExecutionState.ERROR);
            }
            if (execution.state() == DockerTestExecutionState.COMPLETED
                    && !operation.completeNaturally()) {
                execution = incompleteResult(DockerTestExecutionState.CANCELLED, execution);
            } else if (execution.state() == DockerTestExecutionState.TIMED_OUT
                    && !operation.completeDeadline()) {
                execution = incompleteResult(DockerTestExecutionState.CANCELLED, execution);
            } else if (execution.state() == DockerTestExecutionState.CANCELLED) {
                operation.completeCancellation();
            } else if (execution.state() == DockerTestExecutionState.ERROR
                    && !operation.completeFailure()) {
                execution = incompleteResult(DockerTestExecutionState.CANCELLED, execution);
            }

            if (execution.state() == DockerTestExecutionState.CANCELLED
                    || execution.state() == DockerTestExecutionState.TIMED_OUT
                    || execution.state() == DockerTestExecutionState.ERROR) {
                terminateLabAfterTest(requestedLab);
            }
            if (execution.state() == DockerTestExecutionState.COMPLETED) {
                requireTestContextUnchanged(requestedLab, plan);
            }
            Optional<DockerTestCancelCause> cause = execution.state() == DockerTestExecutionState.CANCELLED
                    ? Optional.of(operation.cancelCause())
                    : Optional.empty();
            return new DockerLabTestResult(execution, cause);
        } finally {
            activeTests.remove(requestedLab.id(), operation);
        }
    }

    public boolean cancelTest(String labId) {
        if (labId == null || !labId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("The lab ID is not valid.");
        }
        return cancelActiveTest(labId, DockerTestCancelCause.USER_CANCELLED);
    }

    private void requireTestContextUnchanged(LabRecord requestedLab, ManifestPlan plan) {
        ReentrantLock lock = lockFor(requestedLab.id());
        lock.lock();
        try {
            LabRecord current = labs.findById(requestedLab.id())
                    .orElseThrow(() -> new DockerTestStartException(
                            DockerTestStartException.Reason.LAB_CHANGED));
            if (current.state() != LabState.RUNNING
                    || current.revision() != requestedLab.revision()
                    || !current.projectId().equals(requestedLab.projectId())
                    || !plan.manifestSha256().equals(runningManifestHashes.get(current.id()))) {
                throw new DockerTestStartException(DockerTestStartException.Reason.LAB_CHANGED);
            }
        } finally {
            unlock(lock);
        }
    }

    private void terminateLabAfterTest(LabRecord requestedLab) {
        try {
            stop(requestedLab.id(), requestedLab.revision());
            return;
        } catch (RuntimeException failure) {
            ReentrantLock lock = lockFor(requestedLab.id());
            lock.lock();
            try {
                LabRecord current = labs.findById(requestedLab.id()).orElse(null);
                if (current != null
                        && current.state() == LabState.STOPPED
                        && journal.findOpenByLab(new LabOwnership(
                                        current.id(), current.projectId())).stream()
                                .noneMatch(resource -> resource.type() == DockerResourceType.CONTAINER
                                        && resource.state() == DockerResourceState.ACTIVE)) {
                    return;
                }
            } finally {
                unlock(lock);
            }
            throw new DockerTestTerminationException();
        }
    }

    private boolean cancelActiveTest(String labId, DockerTestCancelCause cause) {
        ActiveTest active = activeTests.get(labId);
        return active != null && active.cancel(cause);
    }

    private static DockerTestExecutionResult incompleteResult(
            DockerTestExecutionState state, DockerTestExecutionResult source) {
        return new DockerTestExecutionResult(
                state,
                java.util.OptionalInt.empty(),
                source.stdout(),
                source.stderr(),
                source.stdoutTruncated(),
                source.stderrTruncated());
    }

    private static DockerTestExecutionResult emptyTestResult(DockerTestExecutionState state) {
        return new DockerTestExecutionResult(
                state,
                java.util.OptionalInt.empty(),
                "",
                "",
                false,
                false);
    }

    private ActiveLogTarget resolveActiveContainer(String labId, String service) {
        if (labId == null || !labId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("The lab ID is not valid.");
        }
        DockerResourceRecord.requireLogicalName(service);
        ReentrantLock lock = lockFor(labId);
        lock.lock();
        try {
            LabRecord current = labs.findById(labId)
                    .orElseThrow(() -> new IllegalStateException("The lab does not exist."));
            LabOwnership ownership = new LabOwnership(current.id(), current.projectId());
            DockerResourceRecord container = journal.findOpen(
                            ownership, DockerResourceType.CONTAINER, service)
                    .filter(resource -> resource.state() == DockerResourceState.ACTIVE)
                    .orElseThrow(DockerActiveServiceNotFoundException::new);
            return new ActiveLogTarget(current, container);
        } finally {
            unlock(lock);
        }
    }

    private void cancelLogSubscriptions(String labId) {
        Set<TrackedLogSubscription> active = logSubscriptions.remove(labId);
        if (active != null) {
            active.forEach(TrackedLogSubscription::close);
        }
    }

    private Map<String, DockerImageMetadata> resolveImages(ManifestPlan plan) {
        Map<String, DockerImageMetadata> byReference = new TreeMap<>();
        List<String> missing = new ArrayList<>();
        for (String reference : plan.images()) {
            Optional<DockerImageMetadata> image = engine.inspectImage(reference);
            if (image.isPresent()) {
                byReference.put(reference, image.orElseThrow());
            } else {
                missing.add(reference);
            }
        }
        if (!missing.isEmpty()) {
            throw new DockerImagesRequiredException(missing);
        }
        Map<String, DockerImageMetadata> byService = new LinkedHashMap<>();
        for (ServicePlan service : plan.services()) {
            ImageSource source = (ImageSource) service.definition().source();
            byService.put(service.id(), byReference.get(source.reference()));
        }
        return Collections.unmodifiableMap(byService);
    }

    private DockerResourceRecord ensurePersistentVolume(
            LabOwnership ownership, String logicalName) {
        Optional<DockerResourceRecord> existing = journal.findOpen(
                ownership, DockerResourceType.VOLUME, logicalName);
        if (existing.isEmpty()) {
            return createTracked(ownership, DockerResourceType.VOLUME, logicalName, engine::createVolume);
        }
        DockerResourceRecord record = existing.orElseThrow();
        if (record.state() != DockerResourceState.ACTIVE) {
            throw new IllegalStateException("A pending persistent volume must be recovered before reuse.");
        }
        engine.verifyVolume(record);
        return record;
    }

    private DockerResourceRecord createTracked(
            LabOwnership ownership,
            DockerResourceType type,
            String logicalName,
            Function<DockerResourceRecord, DockerCreatedResource> create) {
        if (journal.findOpen(ownership, type, logicalName).isPresent()) {
            throw new IllegalStateException("An open Docker resource already uses the planned logical name.");
        }
        DockerResourceRecord reserved = DockerResourceRecord.reserved(
                tokenSupplier.get(), ownership, type, logicalName, now());
        journal.reserve(reserved);
        Instant dispatchedAt = now();
        DockerResourceRecord dispatched = reserved.dispatch(dispatchedAt);
        if (!journal.markDispatched(reserved.ownershipToken(), dispatchedAt)) {
            if (!journal.discardReservation(reserved.ownershipToken(), now())) {
                throw new IllegalStateException("The Docker resource reservation could not be closed.");
            }
            throw new IllegalStateException("The Docker resource dispatch could not be recorded.");
        }
        return finishDispatched(dispatched, create);
    }

    private DockerResourceRecord finishDispatched(
            DockerResourceRecord dispatched,
            Function<DockerResourceRecord, DockerCreatedResource> create) {
        DockerCreatedResource created;
        try {
            created = create.apply(dispatched);
        } catch (DockerCreateWithoutOwnedResourceException noOwnedResource) {
            if (!journal.closeDispatchWithoutResource(dispatched.ownershipToken(), now())) {
                throw new IllegalStateException(
                        "The rejected Docker create remained open for safe recovery.", noOwnedResource);
            }
            throw noOwnedResource;
        } catch (RuntimeException ambiguousFailure) {
            Optional<DockerCreatedResource> recovered = engine.reconcileDispatched(dispatched);
            if (recovered.isEmpty()) {
                throw new IllegalStateException(
                        "The Docker create outcome is still ambiguous and remains journaled.",
                        ambiguousFailure);
            }
            created = recovered.orElseThrow();
            if (ambiguousFailure instanceof DockerOwnershipException) {
                DockerResourceRecord active = activate(dispatched, created);
                rollbackCreatedResource(active);
                throw ambiguousFailure;
            }
        }
        return activate(dispatched, created);
    }

    private DockerResourceRecord activate(
            DockerResourceRecord dispatched, DockerCreatedResource created) {
        Instant activatedAt = now();
        DockerResourceRecord active = dispatched.activate(created, activatedAt);
        if (!journal.activate(
                dispatched.ownershipToken(), created.id(), created.identity(), activatedAt)) {
            if (active.type() == DockerResourceType.VOLUME) {
                throw new IllegalStateException(
                        "The persistent Docker volume remains dispatched for safe recovery.");
            }
            rollbackEngineResource(active);
            if (!journal.closeDispatchWithoutResource(dispatched.ownershipToken(), now())) {
                throw new IllegalStateException(
                        "The rolled-back Docker dispatch could not be closed in its journal.");
            }
            throw new IllegalStateException("The Docker resource could not be activated in its journal.");
        }
        return active;
    }

    private void rollbackCreatedResource(DockerResourceRecord active) {
        if (active.type() == DockerResourceType.VOLUME) {
            return;
        }
        rollbackEngineResource(active);
        if (!journal.markRemoved(active.ownershipToken(), active.engineId().orElseThrow(), now())) {
            throw new IllegalStateException("The rolled-back Docker resource could not be closed.");
        }
    }

    private void rollbackEngineResource(DockerResourceRecord active) {
        if (active.type() == DockerResourceType.CONTAINER) {
            engine.stopContainer(active, STOP_TIMEOUT);
            engine.removeContainer(active);
        } else {
            engine.removeNetwork(active);
        }
    }

    private void cleanupEphemeralResources(
            LabOwnership ownership, CancellationToken cancellation) {
        List<DockerResourceRecord> open = new ArrayList<>(journal.findOpenByLab(ownership));
        List<DockerResourceRecord> active = new ArrayList<>();
        for (DockerResourceRecord resource : open) {
            cancellation.throwIfCancellationRequested();
            if (resource.state() == DockerResourceState.RESERVED) {
                if (!journal.discardReservation(resource.ownershipToken(), now())) {
                    throw new IllegalStateException("A pre-dispatch Docker reservation could not be closed.");
                }
                continue;
            }
            if (resource.state() == DockerResourceState.DISPATCHED) {
                Optional<DockerCreatedResource> match = engine.reconcileDispatched(resource);
                if (match.isEmpty()) {
                    throw new IllegalStateException(
                            "A dispatched Docker create is still ambiguous and remains journaled.");
                }
                resource = activate(resource, match.orElseThrow());
            }
            active.add(resource);
        }

        List<DockerResourceRecord> containers = active.stream()
                .filter(resource -> resource.type() == DockerResourceType.CONTAINER)
                .sorted(java.util.Comparator.comparing(DockerResourceRecord::logicalName).reversed())
                .toList();
        for (DockerResourceRecord container : containers) {
            cancellation.throwIfCancellationRequested();
            engine.stopContainer(container, STOP_TIMEOUT);
            engine.removeContainer(container);
            markActiveRemoved(container);
        }

        for (DockerResourceRecord network : active.stream()
                .filter(resource -> resource.type() == DockerResourceType.NETWORK).toList()) {
            cancellation.throwIfCancellationRequested();
            engine.removeNetwork(network);
            markActiveRemoved(network);
        }

        for (DockerResourceRecord volume : active.stream()
                .filter(resource -> resource.type() == DockerResourceType.VOLUME).toList()) {
            engine.verifyVolume(volume);
        }
    }

    private void markActiveRemoved(DockerResourceRecord resource) {
        if (!journal.markRemoved(
                resource.ownershipToken(), resource.engineId().orElseThrow(), now())) {
            throw new IllegalStateException("A removed Docker resource could not be closed in its journal.");
        }
    }

    private void finishCancelledStart(
            LabRecord starting,
            LabOwnership ownership,
            DockerOperationCancelledException cancellationFailure) {
        Instant stoppingAt = now();
        LabRecord stopping = starting.transitionTo(LabState.STOPPING, stoppingAt);
        boolean stoppingStored = labs.compareAndSetState(
                starting.id(), starting.revision(), LabState.STARTING, LabState.STOPPING, stoppingAt);
        if (!stoppingStored) {
            cancellationFailure.addSuppressed(
                    new IllegalStateException("The cancelled lab could not enter the stopping state."));
            return;
        }
        try {
            cleanupEphemeralResources(ownership, CancellationToken.NONE);
        } catch (RuntimeException cleanupFailure) {
            cancellationFailure.addSuppressed(cleanupFailure);
            storeFailure(
                    stopping,
                    LabFailureCode.CLEANUP_INCOMPLETE,
                    Optional.empty(),
                    true);
            return;
        }
        Instant stoppedAt = now();
        if (!labs.compareAndSetState(
                stopping.id(), stopping.revision(), LabState.STOPPING, LabState.STOPPED, stoppedAt)) {
            cancellationFailure.addSuppressed(
                    new IllegalStateException("The cancelled lab cleanup state could not be stored."));
        }
    }

    private void finishCancelledCommittedRun(
            LabRecord running,
            LabOwnership ownership,
            DockerOperationCancelledException cancellationFailure) {
        runningManifestHashes.remove(running.id());
        runtimeMonitor.cancel(running.id());
        Instant stoppingAt = now();
        LabRecord stopping = running.transitionTo(LabState.STOPPING, stoppingAt);
        if (!labs.compareAndSetState(
                running.id(), running.revision(), LabState.RUNNING, LabState.STOPPING, stoppingAt)) {
            cancellationFailure.addSuppressed(
                    new IllegalStateException("The cancelled running lab could not enter the stopping state."));
            return;
        }
        try {
            cleanupEphemeralResources(ownership, CancellationToken.NONE);
        } catch (RuntimeException cleanupFailure) {
            cancellationFailure.addSuppressed(cleanupFailure);
            storeFailure(
                    stopping,
                    LabFailureCode.CLEANUP_INCOMPLETE,
                    Optional.empty(),
                    true);
            return;
        }
        Instant stoppedAt = now();
        if (!labs.compareAndSetState(
                stopping.id(), stopping.revision(), LabState.STOPPING, LabState.STOPPED, stoppedAt)) {
            cancellationFailure.addSuppressed(
                    new IllegalStateException("The cancelled running lab cleanup state could not be stored."));
        }
    }

    private void finishFailedStart(
            LabRecord starting, LabOwnership ownership, RuntimeException failure) {
        Instant stoppingAt = now();
        LabRecord stopping = starting.transitionTo(LabState.STOPPING, stoppingAt);
        if (!labs.compareAndSetState(
                starting.id(), starting.revision(), LabState.STARTING, LabState.STOPPING, stoppingAt)) {
            failure.addSuppressed(
                    new IllegalStateException("The failed lab could not claim the stopping state."));
            return;
        }
        boolean cleanupIncomplete = false;
        try {
            cleanupEphemeralResources(ownership, CancellationToken.NONE);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            cleanupIncomplete = true;
        }
        FailureClassification classification = classify(failure);
        if (!storeFailure(
                stopping,
                classification.code(),
                classification.service(),
                cleanupIncomplete)) {
            failure.addSuppressed(
                    new IllegalStateException("The failed lab state could not be stored."));
        }
    }

    private void finishFailedCommittedRun(
            LabRecord running, LabOwnership ownership, RuntimeException failure) {
        runningManifestHashes.remove(running.id());
        runtimeMonitor.cancel(running.id());
        Instant stoppingAt = now();
        LabRecord stopping = running.transitionTo(LabState.STOPPING, stoppingAt);
        if (!labs.compareAndSetState(
                running.id(), running.revision(), LabState.RUNNING, LabState.STOPPING, stoppingAt)) {
            failure.addSuppressed(
                    new IllegalStateException("The failed running lab could not claim the stopping state."));
            return;
        }
        boolean cleanupIncomplete = false;
        try {
            cleanupEphemeralResources(ownership, CancellationToken.NONE);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            cleanupIncomplete = true;
        }
        FailureClassification classification = classify(failure);
        if (!storeFailure(
                stopping,
                classification.code(),
                classification.service(),
                cleanupIncomplete)) {
            failure.addSuppressed(
                    new IllegalStateException("The failed running lab state could not be stored."));
        }
    }

    private void handleRuntimeFailure(
            LabRecord monitoredRun,
            LabOwnership ownership,
            DockerRuntimeMonitorPort.RuntimeFailure failure) {
        ReentrantLock lock = lockFor(monitoredRun.id());
        lock.lock();
        try {
            LabRecord current = labs.findById(monitoredRun.id()).orElse(null);
            if (current == null
                    || current.state() != LabState.RUNNING
                    || current.revision() != monitoredRun.revision()
                    || !current.projectId().equals(monitoredRun.projectId())) {
                return;
            }
            cancelActiveTest(monitoredRun.id(), DockerTestCancelCause.LAB_STOPPED);
            runningManifestHashes.remove(monitoredRun.id());
            Instant stoppingAt = now();
            LabRecord stopping = current.transitionTo(LabState.STOPPING, stoppingAt);
            if (!labs.compareAndSetState(
                    current.id(), current.revision(), LabState.RUNNING, LabState.STOPPING, stoppingAt)) {
                return;
            }
            cancelLogSubscriptions(monitoredRun.id());
            boolean cleanupIncomplete = false;
            if (!failure.engineInspectionFailed() && !failure.ownershipMismatch()) {
                try {
                    cleanupEphemeralResources(ownership, CancellationToken.NONE);
                } catch (RuntimeException ignored) {
                    // Exact journal records remain available for an explicit stop retry.
                    cleanupIncomplete = true;
                }
            }
            FailureClassification classification;
            if (failure.engineInspectionFailed()) {
                classification = new FailureClassification(
                        LabFailureCode.DOCKER_UNAVAILABLE, Optional.empty());
            } else if (failure.ownershipMismatch()) {
                classification = new FailureClassification(
                        LabFailureCode.OWNERSHIP_MISMATCH, Optional.empty());
                cleanupIncomplete = true;
            } else {
                classification = classify(failure.serviceFailure());
            }
            storeFailure(
                    stopping,
                    classification.code(),
                    classification.service(),
                    cleanupIncomplete);
        } finally {
            unlock(lock);
        }
    }

    @Override
    public void close() {
        List<String> testingLabs = List.copyOf(activeTests.keySet());
        testingLabs.forEach(labId ->
                cancelActiveTest(labId, DockerTestCancelCause.APPLICATION_SHUTDOWN));
        for (String labId : testingLabs) {
            try {
                stop(labId);
            } catch (RuntimeException ignored) {
                // The active result is marked unavailable if exact cleanup cannot be proved.
            }
        }
        List.copyOf(logSubscriptions.keySet()).forEach(this::cancelLogSubscriptions);
        observationExecutor.shutdownNow();
        runtimeMonitor.close();
    }

    private static ExecutorService newObservationExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4,
                4,
                1,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(4),
                Thread.ofVirtual().name("labdeck-observation-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private record ObservationResult(
            List<DockerServiceObservation> services,
            List<DockerVolumeObservation> volumes,
            boolean networkPresent) {}

    private record ActiveLogTarget(LabRecord lab, DockerResourceRecord container) {}

    private final class TrackedLogSubscription implements DockerLogSubscription {
        private final String labId;
        private final DockerLogSubscription delegate;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TrackedLogSubscription(String labId, DockerLogSubscription delegate) {
            this.labId = labId;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public boolean await(Duration timeout) throws InterruptedException {
            return delegate.await(timeout);
        }

        @Override
        public boolean truncated() {
            return delegate.truncated();
        }

        @Override
        public boolean failed() {
            return delegate.failed();
        }

        @Override
        public boolean closed() {
            return closed.get() || delegate.closed();
        }

        @Override
        public void onClose(Runnable listener) {
            delegate.onClose(listener);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                delegate.close();
            } finally {
                Set<TrackedLogSubscription> active = logSubscriptions.get(labId);
                if (active != null) {
                    active.remove(this);
                    if (active.isEmpty()) {
                        logSubscriptions.remove(labId, active);
                    }
                }
            }
        }
    }

    private boolean storeFailure(
            LabRecord stopping,
            LabFailureCode code,
            Optional<String> service,
            boolean cleanupIncomplete) {
        Instant failedAt = now();
        LabRuntimeFailure failure = new LabRuntimeFailure(
                stopping.id(),
                stopping.revision() + 1,
                code,
                service,
                failedAt,
                cleanupIncomplete);
        return labs.compareAndSetStateWithFailure(
                stopping.id(),
                stopping.revision(),
                LabState.STOPPING,
                failedAt,
                failure);
    }

    private static FailureClassification classify(RuntimeException failure) {
        if (hasCause(failure, DockerStorageFullException.class)) {
            return new FailureClassification(
                    LabFailureCode.DOCKER_STORAGE_FULL, Optional.empty());
        }
        if (hasCause(failure, DockerImagePullException.class)) {
            return new FailureClassification(
                    LabFailureCode.IMAGE_PULL_FAILED, Optional.empty());
        }
        if (failure instanceof DockerPortCollisionException collision) {
            return new FailureClassification(
                    LabFailureCode.HOST_PORT_IN_USE, Optional.of(collision.service()));
        }
        if (failure instanceof DockerServiceReadinessException readinessFailure) {
            LabFailureCode code = switch (readinessFailure.reason()) {
                case EXITED -> LabFailureCode.CONTAINER_EXITED;
                case UNHEALTHY -> LabFailureCode.HEALTHCHECK_UNHEALTHY;
                case TIMED_OUT -> LabFailureCode.STARTUP_TIMEOUT;
                case HEALTH_NOT_REPORTED -> LabFailureCode.CONTAINER_START_FAILED;
            };
            return new FailureClassification(
                    code, readinessFailure.services().stream().findFirst());
        }
        if (failure instanceof DockerOwnershipException) {
            return new FailureClassification(
                    LabFailureCode.OWNERSHIP_MISMATCH, Optional.empty());
        }
        return new FailureClassification(
                LabFailureCode.CONTAINER_START_FAILED, Optional.empty());
    }

    private static boolean hasCause(RuntimeException failure, Class<? extends RuntimeException> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, ServiceDockerPolicy> compileDockerPolicies(ManifestPlan plan) {
        List<ServicePlan> orderedServices = plan.services().stream()
                .sorted(java.util.Comparator.comparing(ServicePlan::id))
                .toList();
        if (orderedServices.stream().map(ServicePlan::id).distinct().count()
                != orderedServices.size()) {
            throw new IllegalArgumentException("Manifest service IDs must be unique.");
        }
        int serviceCount = orderedServices.size();
        long totalMemory = plan.resources().memoryBytes();
        long totalNanoCpus = plan.resources().cpus().movePointRight(9).longValueExact();
        long memoryPerService = totalMemory / serviceCount;
        long memoryRemainder = totalMemory % serviceCount;
        long nanoCpusPerService = totalNanoCpus / serviceCount;
        long nanoCpusRemainder = totalNanoCpus % serviceCount;
        Map<Integer, String> fixedPorts = new TreeMap<>();
        Map<String, ServiceDockerPolicy> policies = new TreeMap<>();
        for (int serviceIndex = 0; serviceIndex < serviceCount; serviceIndex++) {
            ServicePlan service = orderedServices.get(serviceIndex);
            DockerContainerSpec.ResourceLimits resources = new DockerContainerSpec.ResourceLimits(
                    memoryPerService + (serviceIndex < memoryRemainder ? 1 : 0),
                    nanoCpusPerService + (serviceIndex < nanoCpusRemainder ? 1 : 0));
            List<DockerContainerSpec.PublishedPort> ports = service.definition().ports().stream()
                    .map(port -> new DockerContainerSpec.PublishedPort(
                            port.container(), port.host(), port.protocol()))
                    .toList();
            for (DockerContainerSpec.PublishedPort port : ports) {
                port.hostPort().ifPresent(hostPort -> {
                    String first = fixedPorts.putIfAbsent(hostPort, service.id());
                    if (first != null && !first.equals(service.id())) {
                        throw new IllegalArgumentException(
                                "Host port " + hostPort + " is requested by services '"
                                        + first + "' and '" + service.id() + "'.");
                    }
                });
            }
            Optional<DockerContainerSpec.HealthProbe> health = service.definition().healthcheck()
                    .map(value -> new DockerContainerSpec.HealthProbe(
                            value.command(),
                            value.interval(),
                            value.timeout(),
                            value.retries(),
                            value.startPeriod()));
            policies.put(service.id(), new ServiceDockerPolicy(ports, resources, health));
        }
        return Collections.unmodifiableMap(policies);
    }

    private static Duration readinessTimeout(List<PlannedContainer> containers) {
        Duration minimum = Duration.ofSeconds(30);
        Duration maximum = Duration.ofMinutes(15);
        boolean imageHealthTimingUnknown = containers.stream().anyMatch(container ->
                container.specification().healthProbe().isEmpty()
                        && container.specification().imageHealthCheckConfigured());
        if (imageHealthTimingUnknown) {
            return maximum;
        }
        Duration healthBudget = containers.stream()
                .flatMap(container -> container.specification().healthProbe().stream())
                .map(DockerContainerSpec.HealthProbe::readinessBudget)
                .max(Duration::compareTo)
                .orElse(Duration.ZERO);
        Duration bounded = healthBudget.compareTo(minimum) < 0 ? minimum : healthBudget;
        return bounded.compareTo(maximum) > 0 ? maximum : bounded;
    }

    private static void preflightWorkspaceTargets(ManifestPlan plan) {
        for (ServicePlan service : plan.services()) {
            for (var volume : service.definition().volumes()) {
                if (pathsOverlap(plan.workspaceMount(), volume.target())) {
                    throw new IllegalArgumentException(
                            "A named volume cannot hide or overlap the approved workspace mount.");
                }
            }
        }
    }

    private static boolean pathsOverlap(String first, String second) {
        return first.equals(second) || first.startsWith(second + "/") || second.startsWith(first + "/");
    }

    private static void rejectBuildServices(ManifestPlan plan) {
        List<String> builds = plan.services().stream()
                .filter(service -> service.definition().source() instanceof BuildSource)
                .map(ServicePlan::id)
                .toList();
        if (!builds.isEmpty()) {
            throw new IllegalStateException(
                    "Project-local image builds must be prepared before Docker lifecycle start: "
                            + String.join(", ", builds));
        }
    }

    private static void requirePlan(ManifestPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.schemaVersion() != 1 || plan.services().isEmpty()) {
            throw new IllegalArgumentException("A supported non-empty manifest plan is required.");
        }
    }

    private Instant now() {
        Instant value = clock.instant();
        value.toEpochMilli();
        return value;
    }

    private ReentrantLock lockFor(String labId) {
        return labLocks.computeIfAbsent(labId, ignored -> new ReentrantLock());
    }

    private void registerStopRequest(String labId, Long expectedRevision) {
        long revisionKey = expectedRevision == null ? -1L : expectedRevision;
        stopRequests.computeIfAbsent(labId, ignored -> new ConcurrentHashMap<>())
                .merge(revisionKey, 1, (current, added) -> Math.addExact(current, added));
    }

    private void clearStopRequest(String labId, Long expectedRevision) {
        long revisionKey = expectedRevision == null ? -1L : expectedRevision;
        stopRequests.computeIfPresent(labId, (ignored, byRevision) -> {
            byRevision.computeIfPresent(
                    revisionKey, (ignoredRevision, count) -> count == 1 ? null : count - 1);
            return byRevision.isEmpty() ? null : byRevision;
        });
    }

    private boolean hasStopRequest(String labId, long revision) {
        var byRevision = stopRequests.get(labId);
        return byRevision != null
                && (byRevision.containsKey(-1L) || byRevision.containsKey(revision));
    }

    private void unlock(ReentrantLock lock) {
        lock.unlock();
    }

    private static Supplier<String> secureTokenSupplier() {
        SecureRandom random = new SecureRandom();
        return () -> {
            byte[] token = new byte[16];
            random.nextBytes(token);
            return HexFormat.of().formatHex(token);
        };
    }

    private record ServiceDockerPolicy(
            List<DockerContainerSpec.PublishedPort> ports,
            DockerContainerSpec.ResourceLimits resources,
            Optional<DockerContainerSpec.HealthProbe> healthProbe) {

        private ServiceDockerPolicy {
            ports = List.copyOf(ports);
            Objects.requireNonNull(resources, "resources");
            healthProbe = healthProbe == null ? Optional.empty() : healthProbe;
        }
    }

    private record PlannedContainer(
            DockerResourceRecord resource, DockerContainerSpec specification) {
        private PlannedContainer {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(specification, "specification");
        }
    }

    private record FailureClassification(
            LabFailureCode code, Optional<String> service) {
        private FailureClassification {
            Objects.requireNonNull(code, "code");
            service = service == null ? Optional.empty() : service;
        }
    }

    private static final class ActiveStart implements CancellationToken {
        private final long requestedRevision;
        private final java.util.concurrent.atomic.AtomicLong currentRevision;
        private final CancellationToken external;
        private final AtomicBoolean stopRequested = new AtomicBoolean();

        private ActiveStart(long requestedRevision, CancellationToken external) {
            this.requestedRevision = requestedRevision;
            this.currentRevision = new java.util.concurrent.atomic.AtomicLong(requestedRevision);
            this.external = Objects.requireNonNull(external, "external");
        }

        @Override
        public boolean isCancellationRequested() {
            return stopRequested.get() || external.isCancellationRequested();
        }

        private synchronized boolean commitRunning(BooleanSupplier commit) {
            throwIfCancellationRequested();
            return commit.getAsBoolean();
        }

        private synchronized void cancel() {
            stopRequested.set(true);
        }

        private boolean matches(Long expectedRevision) {
            return expectedRevision == null
                    || requestedRevision == expectedRevision.longValue()
                    || currentRevision.get() == expectedRevision.longValue();
        }

        private void advanceRevision(long revision) {
            currentRevision.set(revision);
        }
    }

    private static final class ActiveTest implements CancellationToken {
        private final CancellationToken external;
        private DockerTestCancelCause cancelCause;
        private boolean terminal;

        private ActiveTest(CancellationToken external) {
            this.external = Objects.requireNonNull(external, "external");
        }

        @Override
        public synchronized boolean isCancellationRequested() {
            return cancelCause != null || external.isCancellationRequested();
        }

        private synchronized boolean cancel(DockerTestCancelCause cause) {
            Objects.requireNonNull(cause, "cause");
            if (terminal || cancelCause != null) {
                return false;
            }
            cancelCause = cause;
            return true;
        }

        private synchronized boolean completeNaturally() {
            return completeIfNotCancelled();
        }

        private synchronized boolean completeDeadline() {
            return completeIfNotCancelled();
        }

        private synchronized boolean completeFailure() {
            return completeIfNotCancelled();
        }

        private synchronized void completeCancellation() {
            if (cancelCause == null) {
                cancelCause = external.isCancellationRequested()
                        ? DockerTestCancelCause.USER_CANCELLED
                        : DockerTestCancelCause.APPLICATION_SHUTDOWN;
            }
            terminal = true;
        }

        private synchronized DockerTestCancelCause cancelCause() {
            if (cancelCause == null) {
                if (external.isCancellationRequested()) {
                    return DockerTestCancelCause.USER_CANCELLED;
                }
                throw new IllegalStateException("The test was not cancelled.");
            }
            return cancelCause;
        }

        private boolean completeIfNotCancelled() {
            if (cancelCause != null || external.isCancellationRequested()) {
                if (cancelCause == null) {
                    cancelCause = DockerTestCancelCause.USER_CANCELLED;
                }
                terminal = true;
                return false;
            }
            terminal = true;
            return true;
        }
    }
}
