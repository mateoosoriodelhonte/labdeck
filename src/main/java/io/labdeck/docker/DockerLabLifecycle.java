package io.labdeck.docker;

import io.labdeck.lab.LabRecord;
import io.labdeck.lab.LabRepository;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DockerLabLifecycle {

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PULL_TIMEOUT = Duration.ofMinutes(15);

    private final DockerEnginePort engine;
    private final DockerResourceJournal journal;
    private final LabRepository labs;
    private final ProjectPathPolicy paths;
    private final Clock clock;
    private final Supplier<String> tokenSupplier;
    private final ConcurrentHashMap<String, ReentrantLock> labLocks = new ConcurrentHashMap<>();

    @Autowired
    public DockerLabLifecycle(
            DockerEnginePort engine, DockerResourceJournal journal, LabRepository labs) {
        this(engine, journal, labs, new ProjectPathPolicy(), Clock.systemUTC(), secureTokenSupplier());
    }

    DockerLabLifecycle(
            DockerEnginePort engine,
            DockerResourceJournal journal,
            LabRepository labs,
            ProjectPathPolicy paths,
            Clock clock,
            Supplier<String> tokenSupplier) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.labs = Objects.requireNonNull(labs, "labs");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
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
        ReentrantLock lock = lockFor(requestedLab.id());
        lock.lock();
        try {
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
            Map<String, DockerImageMetadata> images = resolveImages(plan);
            cancellation.throwIfCancellationRequested();

            Instant transitionTime = now();
            LabRecord starting = current.transitionTo(LabState.STARTING, transitionTime);
            if (!labs.compareAndSetState(
                    current.id(), current.revision(), current.state(), LabState.STARTING, transitionTime)) {
                throw new IllegalStateException("Another lab operation won the startup race.");
            }

            LabOwnership ownership = new LabOwnership(starting.id(), starting.projectId());
            try {
                cleanupEphemeralResources(ownership, cancellation);
                cancellation.throwIfCancellationRequested();
                DockerResourceRecord network = createTracked(
                        ownership, DockerResourceType.NETWORK, "lab-network", engine::createNetwork);

                Map<String, DockerResourceRecord> volumes = new TreeMap<>();
                for (String logicalName : plan.volumes()) {
                    cancellation.throwIfCancellationRequested();
                    volumes.put(logicalName, ensurePersistentVolume(ownership, logicalName));
                }

                List<DockerResourceRecord> containers = new ArrayList<>();
                for (ServicePlan service : plan.services()) {
                    cancellation.throwIfCancellationRequested();
                    DockerImageMetadata image = images.get(service.id());
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
                            mounts);
                    containers.add(createTracked(
                            ownership,
                            DockerResourceType.CONTAINER,
                            service.id(),
                            dispatched -> engine.createContainer(dispatched, specification)));
                }

                List<DockerContainerView> views = new ArrayList<>();
                for (DockerResourceRecord container : containers) {
                    cancellation.throwIfCancellationRequested();
                    engine.startContainer(container);
                    views.add(engine.inspectContainer(container));
                }
                cancellation.throwIfCancellationRequested();
                return new DockerStartResult(
                        starting,
                        network.engineId().orElseThrow(),
                        volumes.values().stream().map(value -> value.engineId().orElseThrow()).toList(),
                        views);
            } catch (DockerOperationCancelledException cancellationFailure) {
                finishCancelledStart(starting, ownership, cancellationFailure);
                throw cancellationFailure;
            } catch (RuntimeException failure) {
                try {
                    cleanupEphemeralResources(ownership, CancellationToken.NONE);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                labs.compareAndSetState(
                        starting.id(), starting.revision(), LabState.STARTING, LabState.FAILED, now());
                throw failure;
            }
        } finally {
            unlock(lock);
        }
    }

    public LabRecord stop(String labId) {
        if (labId == null || labId.isBlank()) {
            throw new IllegalArgumentException("The lab ID is required.");
        }
        ReentrantLock lock = lockFor(labId);
        lock.lock();
        try {
            LabRecord current = labs.findById(labId)
                    .orElseThrow(() -> new IllegalStateException("The lab does not exist."));
            LabState cleanupState = current.state();
            LabRecord stopping = current;
            if (Set.of(LabState.STARTING, LabState.RUNNING).contains(current.state())) {
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
            } else if (current.state() != LabState.FAILED) {
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
                    labs.compareAndSetState(
                            stopping.id(), stopping.revision(), LabState.STOPPING, LabState.FAILED, now());
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
        }
        try {
            cleanupEphemeralResources(ownership, CancellationToken.NONE);
        } catch (RuntimeException cleanupFailure) {
            cancellationFailure.addSuppressed(cleanupFailure);
            if (stoppingStored) {
                labs.compareAndSetState(
                        stopping.id(), stopping.revision(), LabState.STOPPING, LabState.FAILED, now());
            }
            return;
        }
        if (stoppingStored) {
            Instant stoppedAt = now();
            if (!labs.compareAndSetState(
                    stopping.id(), stopping.revision(), LabState.STOPPING, LabState.STOPPED, stoppedAt)) {
                cancellationFailure.addSuppressed(
                        new IllegalStateException("The cancelled lab cleanup state could not be stored."));
            }
        }
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
}
