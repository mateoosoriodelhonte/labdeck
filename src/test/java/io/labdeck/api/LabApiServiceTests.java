package io.labdeck.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.labdeck.api.LabApiModels.StartLabRequest;
import io.labdeck.api.LabApiModels.RunTestsRequest;
import io.labdeck.docker.CancellationToken;
import io.labdeck.docker.DockerContainerView;
import io.labdeck.docker.DockerContainerMetrics;
import io.labdeck.docker.DockerHealthStatus;
import io.labdeck.docker.DockerImagePlan;
import io.labdeck.docker.DockerLabLifecycle;
import io.labdeck.docker.DockerObservabilitySnapshot;
import io.labdeck.docker.DockerServiceObservation;
import io.labdeck.docker.DockerStartResult;
import io.labdeck.lab.LabRecord;
import io.labdeck.lab.LabRepository;
import io.labdeck.lab.LabState;
import io.labdeck.lab.LabTestRunService;
import io.labdeck.lab.TestRunRepository;
import io.labdeck.lab.TestRunSnapshot;
import io.labdeck.manifest.ApprovedWorkspacePath;
import io.labdeck.manifest.ManifestPlan;
import io.labdeck.manifest.ManifestPlanCompiler;
import io.labdeck.manifest.ManifestProblem;
import io.labdeck.manifest.ManifestProblemCode;
import io.labdeck.manifest.ManifestValidationException;
import io.labdeck.manifest.ProjectPathPolicy;
import io.labdeck.manifest.RestrictedManifestParser;
import io.labdeck.manifest.WorkspaceManifestLoader;
import io.labdeck.manifest.WorkspaceManifestLoader.LoadedManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LabApiServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-26T20:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private LabRepository labs;
    private DockerLabLifecycle lifecycle;
    private LabTestRunService testRuns;
    private WorkspaceManifestLoader manifests;
    private LabApiService service;
    private LabRecord lab;
    private ManifestPlan plan;
    private ApprovedWorkspacePath approvedWorkspace;

    @BeforeEach
    void setUp() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        plan = new ManifestPlanCompiler().compile(new RestrictedManifestParser().parse(manifest()));
        lab = new LabRecord(
                "lab-1", "project-1", plan.name(), 1, workspace,
                LabState.IMPORTED, 0, NOW, NOW);
        labs = mock(LabRepository.class);
        TestRunRepository tests = mock(TestRunRepository.class);
        lifecycle = mock(DockerLabLifecycle.class);
        manifests = mock(WorkspaceManifestLoader.class);
        when(labs.findById(lab.id())).thenReturn(Optional.of(lab));
        when(labs.findRuntimeFailure(lab.id())).thenReturn(Optional.empty());
        approvedWorkspace = new ProjectPathPolicy().resolveWorkspace(workspace);
        when(manifests.load(workspace)).thenReturn(new LoadedManifest(approvedWorkspace, plan));
        testRuns = mock(LabTestRunService.class);
        service = new LabApiService(
                labs,
                tests,
                manifests,
                lifecycle,
                testRuns,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "fixed-id");
    }

    @Test
    void requiresTheCurrentRevisionAndManifestHashBeforeDockerInspection() {
        assertThatThrownBy(() -> service.startLab(
                        lab.id(), new StartLabRequest(1L, plan.manifestSha256(), List.of())))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("LAB_REVISION_CHANGED");
                    assertThat(exception.properties()).containsEntry("currentRevision", 0L);
                });
        verify(lifecycle, never()).inspectRequiredImages(plan);

        assertThatThrownBy(() -> service.startLab(
                        lab.id(), new StartLabRequest(
                                0L,
                                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                List.of())))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code()).isEqualTo("MANIFEST_CHANGED"));
        verify(lifecycle, never()).inspectRequiredImages(plan);
    }

    @Test
    void requiresExactImageConfirmationBeforePullAndStart() {
        when(lifecycle.inspectRequiredImages(plan)).thenReturn(List.of(
                new DockerImagePlan("busybox:1.37", Optional.empty())));

        assertThatThrownBy(() -> service.startLab(
                        lab.id(), new StartLabRequest(0L, plan.manifestSha256(), List.of())))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("IMAGE_CONFIRMATION_REQUIRED");
                    assertThat(exception.properties()).containsKey("images");
                });
        verify(lifecycle, never()).pullConfirmedImages(
                plan, List.of("busybox:1.37"), CancellationToken.NONE);
        verify(lifecycle, never()).start(
                lab, approvedWorkspace, plan, CancellationToken.NONE);
    }

    @Test
    void pullsOnlyTheConfirmedMissingImageThenStartsTheReviewedPlan() {
        when(lifecycle.inspectRequiredImages(plan)).thenReturn(List.of(
                new DockerImagePlan("busybox:1.37", Optional.empty())));
        LabRecord running = new LabRecord(
                lab.id(), lab.projectId(), lab.name(), 1, lab.workspace(),
                LabState.RUNNING, 2, lab.createdAt(), NOW.plusSeconds(2));
        when(lifecycle.start(
                        lab, approvedWorkspace, plan, CancellationToken.NONE))
                .thenReturn(new DockerStartResult(
                        running, "network-id", List.of(), List.of()));

        var response = service.startLab(
                lab.id(), new StartLabRequest(0L, plan.manifestSha256(), List.of("busybox:1.37")));

        verify(lifecycle).pullConfirmedImages(
                plan, List.of("busybox:1.37"), CancellationToken.NONE);
        verify(lifecycle).start(
                lab, approvedWorkspace, plan, CancellationToken.NONE);
        assertThat(response.lab().state()).isEqualTo("RUNNING");
        assertThat(response.lab().revision()).isEqualTo(2);
    }

    @Test
    void stopReturnsTheNewStateEvenWhenTheManifestCannotBeReadAfterCleanup() {
        LabRecord stopped = new LabRecord(
                lab.id(), lab.projectId(), lab.name(), 1, lab.workspace(),
                LabState.STOPPED, 1, lab.createdAt(), NOW.plusSeconds(1));
        when(lifecycle.stop(lab.id(), 0)).thenReturn(stopped);
        when(manifests.load(lab.workspace())).thenThrow(new ManifestValidationException(List.of(
                new ManifestProblem(
                        ManifestProblemCode.MANIFEST_PARSE_ERROR,
                        "/",
                        "The manifest is not well-formed YAML."))));

        var response = service.stopLab(lab.id(), 0);

        verify(lifecycle).stop(lab.id(), 0);
        assertThat(response.state()).isEqualTo("STOPPED");
        assertThat(response.revision()).isEqualTo(1);
        assertThat(response.plan()).isNull();
    }

    @Test
    void stopReturnsTheNewStateWhenTheWorkspaceIdentityChangedAfterCleanup() throws Exception {
        LabRecord stopped = new LabRecord(
                lab.id(), lab.projectId(), lab.name(), 1, lab.workspace(),
                LabState.STOPPED, 1, lab.createdAt(), NOW.plusSeconds(1));
        Path replacement = Files.createDirectory(temporaryDirectory.resolve("replacement-workspace"));
        when(lifecycle.stop(lab.id(), 0)).thenReturn(stopped);
        when(manifests.load(lab.workspace())).thenReturn(new LoadedManifest(
                new ProjectPathPolicy().resolveWorkspace(replacement), plan));

        var response = service.stopLab(lab.id(), 0);

        verify(lifecycle).stop(lab.id(), 0);
        assertThat(response.state()).isEqualTo("STOPPED");
        assertThat(response.revision()).isEqualTo(1);
        assertThat(response.plan()).isNull();
    }

    @Test
    void serviceInspectionDoesNotDependOnTheMutableManifestOrExposeEngineIds() {
        LabRecord running = new LabRecord(
                lab.id(), lab.projectId(), lab.name(), 1, lab.workspace(),
                LabState.RUNNING, 2, lab.createdAt(), NOW.plusSeconds(2));
        when(labs.findById(lab.id())).thenReturn(Optional.of(running));
        when(manifests.load(lab.workspace())).thenThrow(new AssertionError(
                "Service inspection must not read the mutable manifest."));
        when(lifecycle.inspectObservabilitySnapshot(lab.id())).thenReturn(new DockerObservabilitySnapshot(
                running,
                List.of(new DockerServiceObservation(
                        new DockerContainerView(
                                "private-engine-id",
                                "app",
                                "private-generated-name",
                                "sha256:private-image-id",
                                "running",
                                true,
                                OptionalInt.empty(),
                                DockerHealthStatus.HEALTHY,
                                List.of()),
                        Optional.of("busybox:1.37"),
                        DockerContainerMetrics.UNAVAILABLE,
                        OptionalLong.of(10_000),
                        OptionalLong.of(100),
                        List.of(
                                new io.labdeck.docker.DockerVolumeMountObservation(
                                        "course-data", "/cache", true),
                                new io.labdeck.docker.DockerVolumeMountObservation(
                                        "course-data", "/data", false)))),
                List.of(new io.labdeck.docker.DockerVolumeObservation(
                        "course-data", OptionalLong.empty(), "UNAVAILABLE")),
                true));

        var response = service.listServices(lab.id());

        assertThat(response.services()).singleElement().satisfies(container -> {
            assertThat(container.service()).isEqualTo("app");
            assertThat(container.containerName()).isEqualTo("app");
            assertThat(container.image()).isEqualTo("busybox:1.37");
            assertThat(container.status()).isEqualTo("running");
            assertThat(container.imageSizeBytes()).isEqualTo(10_000);
            assertThat(container.writableLayerBytes()).isEqualTo(100);
        });
        assertThat(response.topology().nodes())
                .extracting(node -> node.id())
                .contains("local-host", "lab-network", "service:app", "volume:course-data");
        assertThat(response.topology().edges())
                .anySatisfy(edge -> {
                    assertThat(edge.kind()).isEqualTo("MOUNTS_VOLUME");
                    assertThat(edge.from()).isEqualTo("service:app");
                    assertThat(edge.to()).isEqualTo("volume:course-data");
                    assertThat(edge.target()).isEqualTo("/data");
                });
        assertThat(response.topology().edges().stream()
                        .filter(edge -> edge.kind().equals("MOUNTS_VOLUME"))
                        .map(edge -> edge.id())
                        .distinct())
                .hasSize(2);
        assertThat(response.storage().knownWritableBytes()).isEqualTo(100);
        assertThat(response.cleanupPlan().readOnly()).isTrue();
        assertThat(response.cleanupPlan().actions())
                .noneMatch(action -> action.action().contains("DELETE"));
        assertThat(response.revision()).isEqualTo(2);
    }

    @Test
    void startsOnlyTheCurrentRunningManifestTestPlan() {
        LabRecord running = new LabRecord(
                lab.id(), lab.projectId(), lab.name(), 1, lab.workspace(),
                LabState.RUNNING, 2, lab.createdAt(), NOW.plusSeconds(2));
        when(labs.findById(lab.id())).thenReturn(Optional.of(running));
        TestRunSnapshot snapshot = new TestRunSnapshot(
                "test-1",
                lab.id(),
                2,
                "app",
                "sha256:" + "b".repeat(64),
                NOW,
                null,
                "RUNNING",
                null,
                0,
                null,
                "",
                "",
                false,
                false,
                true);
        when(testRuns.start(running, plan)).thenReturn(snapshot);

        var response = service.startTest(
                lab.id(), new RunTestsRequest(2L, plan.manifestSha256()));

        verify(lifecycle).validateTestStart(running, plan);
        verify(testRuns).start(running, plan);
        assertThat(response.status()).isEqualTo("RUNNING");
        assertThat(response.testPlanSha256()).isEqualTo("sha256:" + "b".repeat(64));
    }

    @Test
    void includesTheCurrentRunInTestHistoryForPageRecovery() {
        TestRunSnapshot snapshot = new TestRunSnapshot(
                "test-1",
                lab.id(),
                2,
                "app",
                "sha256:" + "b".repeat(64),
                NOW,
                null,
                "RUNNING",
                null,
                25,
                null,
                "",
                "",
                false,
                false,
                true);
        when(testRuns.findActive(lab.id())).thenReturn(Optional.of(snapshot));

        var response = service.testHistory(lab.id(), 20);

        assertThat(response.activeRun()).isNotNull();
        assertThat(response.activeRun().id()).isEqualTo("test-1");
        assertThat(response.activeRun().canCancel()).isTrue();
    }

    private static String manifest() {
        return """
                version: 1
                name: API service fixture
                workspace:
                  mount: /workspace
                services:
                  app:
                    image: busybox:1.37
                resources:
                  memory: 256MiB
                  cpus: 0.5
                tests:
                  service: app
                  command: ["true"]
                  timeout: 5s
                """;
    }
}
