package io.labdeck.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.labdeck.lab.LabRecord;
import io.labdeck.lab.LabRepository;
import io.labdeck.lab.LabState;
import io.labdeck.manifest.ManifestPlan;
import io.labdeck.manifest.ManifestPlanCompiler;
import io.labdeck.manifest.ProjectPathPolicy;
import io.labdeck.manifest.RestrictedManifestParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerLabLifecycleTests {

    private static final Instant NOW = Instant.parse("2026-08-26T20:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void startsByImmutableImageIdAndStopsOnlyJournaledResources() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Files.writeString(workspace.resolve("student.txt"), "keep me");
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        engine.resources.put("foreign-sentinel", null);
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);

        DockerStartResult started = lifecycle.start(lab, plan(), CancellationToken.NONE);

        assertThat(started.lab().state()).isEqualTo(LabState.STARTING);
        assertThat(started.containers()).hasSize(2).allSatisfy(container ->
                assertThat(container.image()).isEqualTo("sha256:immutable-busybox"));
        assertThat(engine.createdSpecifications.values())
                .extracting(DockerContainerSpec::image)
                .containsOnly("sha256:immutable-busybox");
        assertThat(engine.resources).containsKey("foreign-sentinel");

        LabRecord stopped = lifecycle.stop(lab.id());

        assertThat(stopped.state()).isEqualTo(LabState.STOPPED);
        assertThat(engine.resources).containsKey("foreign-sentinel");
        assertThat(engine.resources.keySet()).anyMatch(id -> id.startsWith("volume-"));
        assertThat(engine.resources.keySet()).noneMatch(id -> id.startsWith("container-"));
        assertThat(engine.resources.keySet()).noneMatch(id -> id.startsWith("network-"));
        assertThat(Files.readString(workspace.resolve("student.txt"))).isEqualTo("keep me");
        assertThat(engine.calls).endsWith(
                "stop:container-database",
                "remove:container-database",
                "stop:container-app",
                "remove:container-app",
                "remove:network-lab-network",
                "verify:volume-course-data");
    }

    @Test
    void missingImagesRequireConfirmationBeforeAnyLifecycleMutation() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);

        assertThatThrownBy(() -> lifecycle.start(lab, plan(), CancellationToken.NONE))
                .isInstanceOfSatisfying(DockerImagesRequiredException.class, exception ->
                        assertThat(exception.missingImages()).containsExactly("busybox:1.37"));

        assertThat(labs.findById(lab.id()).orElseThrow().state()).isEqualTo(LabState.IMPORTED);
        assertThat(journal.findOpenByLab(new LabOwnership(lab.id(), lab.projectId()))).isEmpty();
        assertThat(engine.resources).isEmpty();
    }

    @Test
    void passesCancellationIntoAConfirmedPublicImagePull() {
        MemoryLabRepository labs = new MemoryLabRepository(lab(temporaryDirectory));
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);
        CancellationToken cancellation = () -> false;

        lifecycle.pullConfirmedImages(plan(), List.of("busybox:1.37"), cancellation);

        assertThat(engine.pullCancellation).isSameAs(cancellation);
        assertThat(engine.inspectImage("busybox:1.37")).isPresent();
    }

    @Test
    void aPreDispatchReservationNeverAdoptsAnExactLabelSentinel() {
        LabRecord failedLab = lab(temporaryDirectory, LabState.FAILED);
        MemoryLabRepository labs = new MemoryLabRepository(failedLab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        DockerResourceRecord reserved = DockerResourceRecord.reserved(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                new LabOwnership(failedLab.id(), failedLab.projectId()),
                DockerResourceType.CONTAINER,
                "app",
                NOW);
        journal.reserve(reserved);
        engine.resources.put("foreign-exact-label-sentinel", reserved);
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);

        LabRecord stopped = lifecycle.stop(failedLab.id());

        assertThat(stopped.state()).isEqualTo(LabState.STOPPED);
        assertThat(engine.resources).containsKey("foreign-exact-label-sentinel");
        assertThat(journal.findOpenByLab(reserved.ownership())).isEmpty();
    }

    @Test
    void aDelayedCreateRemainsJournaledUntilItCanBeReconciled() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("delayed-workspace"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        engine.failNetworkAfterDispatch = true;
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);

        assertThatThrownBy(() -> lifecycle.start(lab, plan(), CancellationToken.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambiguous");
        DockerResourceRecord dispatched = journal.findOpenByLab(
                        new LabOwnership(lab.id(), lab.projectId()))
                .getFirst();
        assertThat(dispatched.state()).isEqualTo(DockerResourceState.DISPATCHED);
        assertThat(labs.findById(lab.id()).orElseThrow().state()).isEqualTo(LabState.FAILED);

        engine.materializeDelayedNetwork();
        LabRecord stopped = lifecycle.stop(lab.id());

        assertThat(stopped.state()).isEqualTo(LabState.STOPPED);
        assertThat(engine.resources.keySet()).noneMatch(id -> id.startsWith("network-"));
        assertThat(journal.findOpenByLab(dispatched.ownership())).isEmpty();
    }

    @Test
    void cancelledStartCleansEphemeralResourcesAndEndsStopped() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("cancel-workspace"));
        LabRecord lab = lab(workspace);
        MemoryLabRepository labs = new MemoryLabRepository(lab);
        MemoryJournal journal = new MemoryJournal();
        FakeEngine engine = new FakeEngine();
        engine.addImage("busybox:1.37", "sha256:immutable-busybox");
        AtomicBoolean cancelled = new AtomicBoolean();
        engine.afterStart = () -> cancelled.set(true);
        DockerLabLifecycle lifecycle = lifecycle(engine, journal, labs);

        assertThatThrownBy(() -> lifecycle.start(lab, plan(), cancelled::get))
                .isInstanceOf(DockerOperationCancelledException.class);

        assertThat(labs.findById(lab.id()).orElseThrow().state()).isEqualTo(LabState.STOPPED);
        assertThat(engine.resources.keySet()).anyMatch(id -> id.startsWith("volume-"));
        assertThat(engine.resources.keySet()).noneMatch(id -> id.startsWith("container-"));
        assertThat(engine.resources.keySet()).noneMatch(id -> id.startsWith("network-"));
    }

    private DockerLabLifecycle lifecycle(
            FakeEngine engine, MemoryJournal journal, MemoryLabRepository labs) {
        AtomicInteger tokens = new AtomicInteger();
        return new DockerLabLifecycle(
                engine,
                journal,
                labs,
                new ProjectPathPolicy(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "%032x".formatted(tokens.incrementAndGet()));
    }

    private static LabRecord lab(Path workspace) {
        return lab(workspace, LabState.IMPORTED);
    }

    private static LabRecord lab(Path workspace, LabState state) {
        return new LabRecord(
                "lab-a", "project-a", "Lifecycle lab", 1, workspace,
                state, 0, NOW, NOW);
    }

    private static ManifestPlan plan() {
        String yaml = """
                version: 1
                name: Lifecycle lab
                workspace:
                  mount: /workspace
                services:
                  app:
                    image: busybox:1.37
                    command: ["sleep", "30"]
                    volumes:
                      - name: course-data
                        target: /data
                  database:
                    image: busybox:1.37
                    command: ["sleep", "30"]
                """;
        return new ManifestPlanCompiler().compile(new RestrictedManifestParser().parse(yaml));
    }

    private static final class MemoryLabRepository implements LabRepository {
        private final Map<String, LabRecord> records = new LinkedHashMap<>();

        private MemoryLabRepository(LabRecord lab) {
            records.put(lab.id(), lab);
        }

        @Override
        public void create(LabRecord lab) {
            records.put(lab.id(), lab);
        }

        @Override
        public Optional<LabRecord> findById(String id) {
            return Optional.ofNullable(records.get(id));
        }

        @Override
        public List<LabRecord> findAll() {
            return List.copyOf(records.values());
        }

        @Override
        public boolean compareAndSetState(
                String id, long revision, LabState expected, LabState next, Instant updatedAt) {
            LabRecord current = records.get(id);
            if (current == null || current.revision() != revision || current.state() != expected) {
                return false;
            }
            records.put(id, current.transitionTo(next, updatedAt));
            return true;
        }
    }

    private static final class MemoryJournal implements DockerResourceJournal {
        private final Map<String, DockerResourceRecord> records = new LinkedHashMap<>();

        @Override
        public void reserve(DockerResourceRecord resource) {
            if (findOpen(resource.ownership(), resource.type(), resource.logicalName()).isPresent()) {
                throw new IllegalStateException("duplicate open resource");
            }
            records.put(resource.ownershipToken(), resource);
        }

        @Override
        public boolean markDispatched(String token, Instant updatedAt) {
            DockerResourceRecord current = records.get(token);
            if (current == null || current.state() != DockerResourceState.RESERVED) {
                return false;
            }
            records.put(token, current.dispatch(updatedAt));
            return true;
        }

        @Override
        public boolean activate(
                String token, String engineId, Optional<String> engineIdentity, Instant updatedAt) {
            DockerResourceRecord current = records.get(token);
            if (current == null || current.state() != DockerResourceState.DISPATCHED) {
                return false;
            }
            records.put(token, current.activate(
                    new DockerCreatedResource(engineId, engineIdentity), updatedAt));
            return true;
        }

        @Override
        public boolean discardReservation(String token, Instant updatedAt) {
            return discardPending(token, DockerResourceState.RESERVED, updatedAt);
        }

        @Override
        public boolean closeDispatchWithoutResource(String token, Instant updatedAt) {
            return discardPending(token, DockerResourceState.DISPATCHED, updatedAt);
        }

        @Override
        public boolean markRemoved(String token, String expectedEngineId, Instant updatedAt) {
            DockerResourceRecord current = records.get(token);
            if (current == null || current.state() == DockerResourceState.REMOVED
                    || !current.engineId().equals(Optional.ofNullable(expectedEngineId))) {
                return false;
            }
            records.put(token, new DockerResourceRecord(
                    current.ownershipToken(), current.ownership(), current.type(), current.logicalName(),
                    current.engineId(), current.engineIdentity(), DockerResourceState.REMOVED,
                    current.createdAt(), updatedAt));
            return true;
        }

        private boolean discardPending(
                String token, DockerResourceState expected, Instant updatedAt) {
            DockerResourceRecord current = records.get(token);
            if (current == null || current.state() != expected || current.engineId().isPresent()) {
                return false;
            }
            records.put(token, new DockerResourceRecord(
                    current.ownershipToken(), current.ownership(), current.type(), current.logicalName(),
                    Optional.empty(), Optional.empty(), DockerResourceState.REMOVED,
                    current.createdAt(), updatedAt));
            return true;
        }

        @Override
        public Optional<DockerResourceRecord> findOpen(
                LabOwnership ownership, DockerResourceType type, String logicalName) {
            return records.values().stream()
                    .filter(record -> record.ownership().equals(ownership))
                    .filter(record -> record.type() == type && record.logicalName().equals(logicalName))
                    .filter(record -> record.state() != DockerResourceState.REMOVED)
                    .findFirst();
        }

        @Override
        public List<DockerResourceRecord> findOpenByLab(LabOwnership ownership) {
            return records.values().stream()
                    .filter(record -> record.ownership().equals(ownership))
                    .filter(record -> record.state() != DockerResourceState.REMOVED)
                    .toList();
        }
    }

    private static final class FakeEngine implements DockerEnginePort {
        private final Map<String, DockerImageMetadata> images = new LinkedHashMap<>();
        private final Map<String, DockerResourceRecord> resources = new LinkedHashMap<>();
        private final Map<String, DockerContainerSpec> createdSpecifications = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();
        private CancellationToken pullCancellation;
        private boolean failNetworkAfterDispatch;
        private DockerResourceRecord delayedNetwork;
        private Runnable afterStart = () -> {};

        void addImage(String reference, String id) {
            DockerImageMetadata metadata = new DockerImageMetadata(id, 123, Set.of());
            images.put(reference, metadata);
            images.put(id, metadata);
        }

        @Override
        public void verifyAvailable() {
            calls.add("ping");
        }

        @Override
        public Optional<DockerImageMetadata> inspectImage(String reference) {
            return Optional.ofNullable(images.get(reference));
        }

        @Override
        public void pullPublicImageAfterConfirmation(
                String reference, Duration timeout, CancellationToken cancellation) {
            pullCancellation = cancellation;
            addImage(reference, "sha256:confirmed-pull");
        }

        @Override
        public Optional<DockerCreatedResource> reconcileDispatched(DockerResourceRecord dispatched) {
            return resources.entrySet().stream()
                    .filter(entry -> dispatched.equals(entry.getValue()))
                    .map(entry -> created(entry.getKey(), entry.getValue()))
                    .findFirst();
        }

        @Override
        public DockerCreatedResource createNetwork(DockerResourceRecord dispatched) {
            if (failNetworkAfterDispatch) {
                failNetworkAfterDispatch = false;
                delayedNetwork = dispatched;
                throw new IllegalStateException("simulated lost create response");
            }
            return create("network", dispatched, null);
        }

        void materializeDelayedNetwork() {
            DockerResourceRecord dispatched = java.util.Objects.requireNonNull(delayedNetwork);
            resources.put("network-" + dispatched.logicalName(), dispatched);
            delayedNetwork = null;
        }

        @Override
        public DockerCreatedResource createVolume(DockerResourceRecord dispatched) {
            return create("volume", dispatched, null);
        }

        @Override
        public DockerCreatedResource createContainer(
                DockerResourceRecord dispatched, DockerContainerSpec specification) {
            DockerCreatedResource created = create("container", dispatched, specification);
            createdSpecifications.put(created.id(), specification);
            return created;
        }

        private DockerCreatedResource create(
                String prefix, DockerResourceRecord dispatched, DockerContainerSpec specification) {
            String id = prefix + "-" + dispatched.logicalName();
            resources.put(id, dispatched);
            calls.add("create:" + id);
            return created(id, dispatched);
        }

        private static DockerCreatedResource created(String id, DockerResourceRecord dispatched) {
            return dispatched.type() == DockerResourceType.VOLUME
                    ? DockerCreatedResource.identified(id, "created-" + dispatched.ownershipToken())
                    : DockerCreatedResource.withImmutableId(id);
        }

        @Override
        public DockerContainerView inspectContainer(DockerResourceRecord active) {
            String id = active.engineId().orElseThrow();
            requireOwned(id, active);
            DockerContainerSpec specification = createdSpecifications.get(id);
            return new DockerContainerView(id, active.logicalName(), specification.image(), "running", true);
        }

        @Override
        public void startContainer(DockerResourceRecord active) {
            requireOwned(active.engineId().orElseThrow(), active);
            calls.add("start:" + active.engineId().orElseThrow());
            afterStart.run();
        }

        @Override
        public void stopContainer(DockerResourceRecord active, Duration timeout) {
            requireOwned(active.engineId().orElseThrow(), active);
            calls.add("stop:" + active.engineId().orElseThrow());
        }

        @Override
        public void removeContainer(DockerResourceRecord active) {
            String id = active.engineId().orElseThrow();
            requireOwned(id, active);
            resources.remove(id);
            calls.add("remove:" + id);
        }

        @Override
        public void removeNetwork(DockerResourceRecord active) {
            String id = active.engineId().orElseThrow();
            requireOwned(id, active);
            resources.remove(id);
            calls.add("remove:" + id);
        }

        @Override
        public void verifyVolume(DockerResourceRecord active) {
            requireOwned(active.engineId().orElseThrow(), active);
            calls.add("verify:" + active.engineId().orElseThrow());
        }

        private void requireOwned(String id, DockerResourceRecord active) {
            DockerResourceRecord reserved = resources.get(id);
            if (reserved == null || !reserved.ownershipToken().equals(active.ownershipToken())) {
                throw new DockerOwnershipException("not owned");
            }
        }
    }
}
