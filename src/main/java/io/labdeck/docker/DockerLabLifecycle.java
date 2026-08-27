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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DockerLabLifecycle implements AutoCloseable {

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PULL_TIMEOUT = Duration.ofMinutes(15);

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

    public DockerStartResult start(
            LabRecord requestedLab, ManifestPlan plan, CancellationToken cancellation) {
        Objects.requireNonNull(requestedLab, "requestedLab");
        requirePlan(plan);
        rejectBuildServices(plan);
        if (requestedLab.manifestVersion() != plan.schemaVersion()
                || !requestedLab.name().equals(plan.name())) {
            throw new IllegalArgumentException("The lab record and manifest plan do not match.");
        }
        cancellation = cancellation == null ? CancellationToken.NONE : cancellation;
        Map<String, ServiceDockerPolicy> policies = compileDockerPolicies(plan);
        ActiveStart operation = new ActiveStart(cancellation);
        if (activeStarts.putIfAbsent(requestedLab.id(), operation) != null) {
            throw new IllegalStateException("Another start operation is already active for this lab.");
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

            ApprovedWorkspacePath workspace = paths.resolveWorkspace(current.workspace());
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
                            service.definition().workingDirectory(),
                            service.definition().command(),
                            service.definition().environment(),
                            workspace,
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
        if (labId == null || labId.isBlank()) {
            throw new IllegalArgumentException("The lab ID is required.");
        }
        runtimeMonitor.cancel(labId);
        ActiveStart active = activeStarts.get(labId);
        if (active != null) {
            active.cancel();
        }
        ReentrantLock lock = lockFor(labId);
        lock.lock();
        try {
            LabRecord current = labs.findById(labId)
                    .orElseThrow(() -> new IllegalStateException("The lab does not exist."));
            LabState cleanupState = current.state();
            LabRecord stopping = current;
            if (Set.of(LabState.STARTING, LabState.RUNNING, LabState.FAILED).contains(current.state())) {
                Instant transitionTime = now();
                stopping = current.transitionTo(LabState.STOPPING, transitionTime);
                if (!labs.compareAndSetState(
                        current.id(), current.revision(), current.state(), LabState.STOPPING, transitionTime)) {
                    throw new IllegalStateException("Another lab operation won the stop race.");
                }
                cleanupState = LabState.STOPPING;
            } else if (current.state() == LabState.IMPORTED) {
                Instant transitionTime = now();
                LabRecord stopped = current.transitionTo(LabState.STOPPED, transitionTime);
                if (!labs.compareAndSetState(
                        current.id(), current.revision(), LabState.IMPORTED, LabState.STOPPED, transitionTime)) {
                    throw new IllegalStateException("Another lab operation won the stop race.");
                }
                return stopped;
            } else if (current.state() == LabState.STOPPED) {
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
            Instant stoppingAt = now();
            LabRecord stopping = current.transitionTo(LabState.STOPPING, stoppingAt);
            if (!labs.compareAndSetState(
                    current.id(), current.revision(), LabState.RUNNING, LabState.STOPPING, stoppingAt)) {
                return;
            }
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
        runtimeMonitor.close();
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
        private final CancellationToken external;
        private final AtomicBoolean stopRequested = new AtomicBoolean();

        private ActiveStart(CancellationToken external) {
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
    }
}
