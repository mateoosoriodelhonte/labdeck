package io.labdeck.api;

import static io.labdeck.api.LabApiModels.API_VERSION;

import io.labdeck.api.LabApiModels.FailureResponse;
import io.labdeck.api.LabApiModels.ImageRequirementResponse;
import io.labdeck.api.LabApiModels.LabDetailResponse;
import io.labdeck.api.LabApiModels.LabListResponse;
import io.labdeck.api.LabApiModels.LabStartResponse;
import io.labdeck.api.LabApiModels.LabSummaryResponse;
import io.labdeck.api.LabApiModels.LogListResponse;
import io.labdeck.api.LabApiModels.ManifestPlanResponse;
import io.labdeck.api.LabApiModels.PortMappingResponse;
import io.labdeck.api.LabApiModels.PortPlanResponse;
import io.labdeck.api.LabApiModels.ResourcePlanResponse;
import io.labdeck.api.LabApiModels.ServiceListResponse;
import io.labdeck.api.LabApiModels.ServicePlanResponse;
import io.labdeck.api.LabApiModels.ServiceStatusResponse;
import io.labdeck.api.LabApiModels.SettingsResponse;
import io.labdeck.api.LabApiModels.StartLabRequest;
import io.labdeck.api.LabApiModels.TemplateListResponse;
import io.labdeck.api.LabApiModels.TestHistoryResponse;
import io.labdeck.api.LabApiModels.TestPlanResponse;
import io.labdeck.api.LabApiModels.TestRunResponse;
import io.labdeck.api.LabApiModels.VolumePlanResponse;
import io.labdeck.docker.CancellationToken;
import io.labdeck.docker.DockerContainerView;
import io.labdeck.docker.DockerImagePlan;
import io.labdeck.docker.DockerLabLifecycle;
import io.labdeck.docker.DockerStartResult;
import io.labdeck.lab.LabRecord;
import io.labdeck.lab.LabRepository;
import io.labdeck.lab.LabRuntimeFailure;
import io.labdeck.lab.LabState;
import io.labdeck.lab.TestRunRecord;
import io.labdeck.lab.TestRunRepository;
import io.labdeck.manifest.LabManifest.BuildSource;
import io.labdeck.manifest.LabManifest.ImageSource;
import io.labdeck.manifest.ManifestPlan;
import io.labdeck.manifest.WorkspaceManifestLoader;
import io.labdeck.manifest.WorkspaceManifestLoader.LoadedManifest;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LabApiService {

    private static final String ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}";

    private final LabRepository labs;
    private final TestRunRepository tests;
    private final WorkspaceManifestLoader manifests;
    private final DockerLabLifecycle lifecycle;
    private final Clock clock;
    private final Supplier<String> identifier;

    @Autowired
    public LabApiService(
            LabRepository labs,
            TestRunRepository tests,
            WorkspaceManifestLoader manifests,
            DockerLabLifecycle lifecycle) {
        this(labs, tests, manifests, lifecycle, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    LabApiService(
            LabRepository labs,
            TestRunRepository tests,
            WorkspaceManifestLoader manifests,
            DockerLabLifecycle lifecycle,
            Clock clock,
            Supplier<String> identifier) {
        this.labs = Objects.requireNonNull(labs, "labs");
        this.tests = Objects.requireNonNull(tests, "tests");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.identifier = Objects.requireNonNull(identifier, "identifier");
    }

    public LabListResponse listLabs() {
        return new LabListResponse(
                API_VERSION,
                labs.findAll().stream().map(LabApiService::summary).toList());
    }

    public LabDetailResponse importLab(String workspaceText) {
        LoadedManifest loaded = manifests.load(parseWorkspace(workspaceText));
        Optional<LabRecord> existing = labs.findAll().stream()
                .filter(lab -> lab.workspace().equals(loaded.workspace().path()))
                .findFirst();
        if (existing.isPresent()) {
            if (!existing.orElseThrow().name().equals(loaded.plan().name())) {
                throw conflict(
                        "WORKSPACE_ALREADY_IMPORTED",
                        "Workspace already imported",
                        "This workspace is already linked to a lab with a different manifest name.",
                        Map.of("labId", existing.orElseThrow().id()));
            }
            return detail(existing.orElseThrow(), loaded.plan());
        }

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
        String suffix = requireIdentifier(identifier.get());
        LabRecord lab = new LabRecord(
                "lab-" + suffix,
                "project-" + suffix,
                loaded.plan().name(),
                loaded.plan().schemaVersion(),
                loaded.workspace().path(),
                LabState.IMPORTED,
                0,
                now,
                now);
        try {
            labs.create(lab);
        } catch (DataIntegrityViolationException exception) {
            throw conflict(
                    "WORKSPACE_ALREADY_IMPORTED",
                    "Workspace already imported",
                    "This workspace is already linked to a LabDeck lab.",
                    Map.of());
        }
        return detail(lab, loaded.plan());
    }

    public LabDetailResponse getLab(String id) {
        LabRecord lab = findLab(id);
        return detail(lab, loadPlan(lab));
    }

    public LabStartResponse startLab(String id, StartLabRequest request) {
        Objects.requireNonNull(request, "request");
        LabRecord lab = findLab(id);
        requireRevision(lab, request.expectedRevision());
        ManifestPlan plan = loadPlan(lab);
        if (!plan.manifestSha256().equals(request.expectedManifestSha256())) {
            throw conflict(
                    "MANIFEST_CHANGED",
                    "Manifest changed",
                    "The manifest changed after the plan was reviewed. Review the current plan before starting.",
                    Map.of("currentManifestSha256", plan.manifestSha256()));
        }

        List<DockerImagePlan> imagePlan = lifecycle.inspectRequiredImages(plan);
        List<String> missing = imagePlan.stream()
                .filter(DockerImagePlan::needsDownload)
                .map(DockerImagePlan::reference)
                .sorted()
                .toList();
        Set<String> confirmed = Set.copyOf(request.confirmedImageDownloads());
        if (confirmed.size() != request.confirmedImageDownloads().size()) {
            throw invalid("Each confirmed image may appear only once.");
        }
        if (!confirmed.equals(Set.copyOf(missing))) {
            List<ImageRequirementResponse> requirements = imagePlan.stream()
                    .map(image -> new ImageRequirementResponse(
                            image.reference(),
                            !image.needsDownload(),
                            image.localImage().map(metadata -> metadata.sizeBytes()).orElse(null)))
                    .toList();
            throw conflict(
                    "IMAGE_CONFIRMATION_REQUIRED",
                    "Image confirmation required",
                    "Confirm exactly the missing public images before LabDeck downloads them.",
                    Map.of("images", requirements));
        }
        if (!missing.isEmpty()) {
            lifecycle.pullConfirmedImages(plan, missing, CancellationToken.NONE);
        }

        DockerStartResult result = lifecycle.start(lab, plan, CancellationToken.NONE);
        Map<String, String> images = plan.services().stream().collect(Collectors.toUnmodifiableMap(
                service -> service.id(),
                service -> sourceLabel(service.definition().source())));
        return new LabStartResponse(
                API_VERSION,
                detail(result.lab(), plan),
                result.containers().stream()
                        .map(container -> service(container, images.getOrDefault(container.service(), "unknown")))
                        .toList());
    }

    public LabDetailResponse stopLab(String id, long expectedRevision) {
        LabRecord lab = findLab(id);
        requireRevision(lab, expectedRevision);
        LabRecord stopped = lifecycle.stop(id, expectedRevision);
        return detail(stopped, loadPlan(stopped));
    }

    public ServiceListResponse listServices(String id) {
        LabRecord lab = findLab(id);
        ManifestPlan plan = loadPlan(lab);
        Map<String, String> images = plan.services().stream().collect(Collectors.toUnmodifiableMap(
                service -> service.id(),
                service -> sourceLabel(service.definition().source())));
        List<ServiceStatusResponse> services = lifecycle.inspectServices(id).stream()
                .map(container -> service(container, images.getOrDefault(container.service(), "unknown")))
                .toList();
        LabRecord current = findLab(id);
        return new ServiceListResponse(API_VERSION, current.id(), current.revision(), services);
    }

    public TestHistoryResponse testHistory(String id, int limit) {
        LabRecord lab = findLab(id);
        return new TestHistoryResponse(
                API_VERSION,
                lab.id(),
                tests.findRecentByLab(lab.id(), limit).stream().map(LabApiService::testRun).toList());
    }

    public LogListResponse logs(String id) {
        LabRecord lab = findLab(id);
        return new LogListResponse(API_VERSION, lab.id(), "PLANNED", List.of(), false);
    }

    public TemplateListResponse templates() {
        return new TemplateListResponse(API_VERSION, "PLANNED", List.of());
    }

    public SettingsResponse settings() {
        return new SettingsResponse(
                API_VERSION,
                "127.0.0.1",
                false,
                false,
                false,
                "PLANNED",
                "PLANNED",
                "PLANNED");
    }

    private LabRecord findLab(String id) {
        requireId(id);
        return labs.findById(id).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "LAB_NOT_FOUND",
                "Lab not found",
                "No LabDeck lab has that ID."));
    }

    private ManifestPlan loadPlan(LabRecord lab) {
        LoadedManifest loaded = manifests.load(lab.workspace());
        if (!loaded.workspace().path().equals(lab.workspace())) {
            throw conflict(
                    "WORKSPACE_CHANGED",
                    "Workspace changed",
                    "The stored workspace no longer has the approved identity.",
                    Map.of());
        }
        return loaded.plan();
    }

    private LabDetailResponse detail(LabRecord lab, ManifestPlan plan) {
        FailureResponse failure = labs.findRuntimeFailure(lab.id())
                .map(LabApiService::failure)
                .orElse(null);
        return new LabDetailResponse(
                API_VERSION,
                lab.id(),
                lab.name(),
                lab.workspace().toString(),
                lab.state().name(),
                lab.revision(),
                lab.createdAt(),
                lab.updatedAt(),
                plan(plan),
                failure);
    }

    private static LabSummaryResponse summary(LabRecord lab) {
        return new LabSummaryResponse(
                lab.id(), lab.name(), lab.state().name(), lab.revision(), lab.updatedAt());
    }

    private static ManifestPlanResponse plan(ManifestPlan plan) {
        return new ManifestPlanResponse(
                plan.schemaVersion(),
                plan.manifestSha256(),
                plan.name(),
                plan.workspaceMount(),
                new ResourcePlanResponse(
                        plan.resources().memoryBytes(), plan.resources().cpus().toPlainString()),
                plan.services().stream().map(service -> new ServicePlanResponse(
                        service.id(),
                        service.definition().source() instanceof ImageSource ? "IMAGE" : "PROJECT_BUILD",
                        sourceLabel(service.definition().source()),
                        service.definition().workingDirectory(),
                        service.definition().command(),
                        List.copyOf(service.definition().environment().navigableKeySet()),
                        service.definition().ports().stream()
                                .map(port -> new PortPlanResponse(
                                        port.container(), port.host().orElse(null), port.protocol()))
                                .toList(),
                        service.definition().healthcheck().isPresent(),
                        service.definition().volumes().stream()
                                .map(volume -> new VolumePlanResponse(
                                        volume.name(), volume.target(), volume.readOnly()))
                                .toList()))
                        .toList(),
                plan.images(),
                plan.volumes(),
                plan.tests().map(test -> new TestPlanResponse(
                        test.service(), test.command(), test.timeout().toSeconds())).orElse(null));
    }

    private static String sourceLabel(io.labdeck.manifest.LabManifest.ServiceSource source) {
        if (source instanceof ImageSource image) {
            return image.reference();
        }
        BuildSource build = (BuildSource) source;
        return build.context() + "/" + build.dockerfile();
    }

    private static FailureResponse failure(LabRuntimeFailure failure) {
        return new FailureResponse(
                failure.code().name(),
                failure.service().orElse(null),
                failure.occurredAt(),
                failure.retryable(),
                failure.cleanupIncomplete(),
                failure.safeMessage());
    }

    private static ServiceStatusResponse service(DockerContainerView container, String image) {
        return new ServiceStatusResponse(
                container.service(),
                image,
                container.status(),
                container.running(),
                container.exitCode().isPresent() ? container.exitCode().getAsInt() : null,
                container.health().name(),
                container.ports().stream()
                        .map(port -> new PortMappingResponse(
                                port.containerPort(),
                                port.hostAddress(),
                                port.hostPort(),
                                port.protocol(),
                                "127.0.0.1:" + port.hostPort()))
                        .toList());
    }

    private static TestRunResponse testRun(TestRunRecord run) {
        return new TestRunResponse(
                run.id(),
                run.recordedAt(),
                run.status().name(),
                run.duration().toMillis(),
                run.exitCode().isPresent() ? run.exitCode().getAsInt() : null,
                run.stdout().text(),
                run.stderr().text(),
                run.outputTruncated());
    }

    private static Path parseWorkspace(String workspace) {
        try {
            return Path.of(workspace);
        } catch (InvalidPathException exception) {
            throw invalid("The workspace path is not valid.");
        }
    }

    private static String requireIdentifier(String value) {
        String normalized = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9-]{0,47}")) {
            throw new IllegalStateException("The generated lab identifier is not valid.");
        }
        return normalized;
    }

    private static void requireId(String id) {
        if (id == null || !id.matches(ID_PATTERN)) {
            throw invalid("The lab ID is not valid.");
        }
    }

    private static void requireRevision(LabRecord lab, long expectedRevision) {
        if (lab.revision() != expectedRevision) {
            throw conflict(
                    "LAB_REVISION_CHANGED",
                    "Lab changed",
                    "The lab changed after it was loaded. Refresh it before retrying.",
                    Map.of("currentRevision", lab.revision(), "currentState", lab.state().name()));
        }
    }

    private static ApiException invalid(String detail) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "Invalid request",
                detail);
    }

    private static ApiException conflict(
            String code,
            String title,
            String detail,
            Map<String, Object> properties) {
        return new ApiException(HttpStatus.CONFLICT, code, title, detail, properties);
    }
}
