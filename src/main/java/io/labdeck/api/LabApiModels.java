package io.labdeck.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class LabApiModels {

    public static final String API_VERSION = "v1";

    private LabApiModels() {}

    public record ImportLabRequest(
            @NotBlank @Size(max = 4096) String workspace) {}

    public record StartLabRequest(
            @PositiveOrZero long expectedRevision,
            @NotBlank
            @Pattern(regexp = "sha256:[a-f0-9]{64}")
            String expectedManifestSha256,
            @Size(max = 32)
            List<@NotBlank @Size(max = 255) String> confirmedImageDownloads) {

        public StartLabRequest {
            confirmedImageDownloads = confirmedImageDownloads == null
                    ? List.of()
                    : List.copyOf(confirmedImageDownloads);
        }
    }

    public record StopLabRequest(@PositiveOrZero long expectedRevision) {}

    public record LabListResponse(String apiVersion, List<LabSummaryResponse> labs) {
        public LabListResponse {
            labs = List.copyOf(labs);
        }
    }

    public record LabSummaryResponse(
            String id,
            String name,
            String state,
            long revision,
            Instant updatedAt) {}

    public record LabDetailResponse(
            String apiVersion,
            String id,
            String name,
            String workspace,
            String state,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            ManifestPlanResponse plan,
            FailureResponse failure) {}

    public record ManifestPlanResponse(
            int schemaVersion,
            String manifestSha256,
            String name,
            String workspaceMount,
            ResourcePlanResponse resources,
            List<ServicePlanResponse> services,
            List<String> images,
            List<String> volumes,
            TestPlanResponse tests) {

        public ManifestPlanResponse {
            services = List.copyOf(services);
            images = List.copyOf(images);
            volumes = List.copyOf(volumes);
        }
    }

    public record ResourcePlanResponse(long memoryBytes, String cpus) {}

    public record ServicePlanResponse(
            String id,
            String sourceType,
            String source,
            String workingDirectory,
            List<String> command,
            List<String> environmentKeys,
            List<PortPlanResponse> ports,
            boolean hasHealthCheck,
            List<VolumePlanResponse> volumes) {

        public ServicePlanResponse {
            command = List.copyOf(command);
            environmentKeys = List.copyOf(environmentKeys);
            ports = List.copyOf(ports);
            volumes = List.copyOf(volumes);
        }
    }

    public record PortPlanResponse(
            int containerPort,
            Integer requestedHostPort,
            String protocol) {}

    public record VolumePlanResponse(String name, String target, boolean readOnly) {}

    public record TestPlanResponse(String service, List<String> command, long timeoutSeconds) {
        public TestPlanResponse {
            command = List.copyOf(command);
        }
    }

    public record FailureResponse(
            String code,
            String service,
            Instant occurredAt,
            boolean retryable,
            boolean cleanupIncomplete,
            String message) {}

    public record ImageRequirementResponse(
            String reference,
            boolean availableLocally,
            Long localSizeBytes) {}

    public record LabStartResponse(
            String apiVersion,
            LabDetailResponse lab,
            List<ServiceStatusResponse> services) {
        public LabStartResponse {
            services = List.copyOf(services);
        }
    }

    public record ServiceListResponse(
            String apiVersion,
            String labId,
            long revision,
            List<ServiceStatusResponse> services) {
        public ServiceListResponse {
            services = List.copyOf(services);
        }
    }

    public record ServiceStatusResponse(
            String service,
            String image,
            String status,
            boolean running,
            Integer exitCode,
            String health,
            List<PortMappingResponse> ports) {
        public ServiceStatusResponse {
            ports = List.copyOf(ports);
        }
    }

    public record PortMappingResponse(
            int containerPort,
            String hostAddress,
            int hostPort,
            String protocol,
            String endpoint) {}

    public record TestHistoryResponse(
            String apiVersion,
            String labId,
            List<TestRunResponse> runs) {
        public TestHistoryResponse {
            runs = List.copyOf(runs);
        }
    }

    public record TestRunResponse(
            String id,
            Instant recordedAt,
            String status,
            long durationMillis,
            Integer exitCode,
            String stdout,
            String stderr,
            boolean outputTruncated) {}

    public record LogListResponse(
            String apiVersion,
            String labId,
            String capability,
            List<LogLineResponse> lines,
            boolean truncated) {
        public LogListResponse {
            lines = List.copyOf(lines);
        }
    }

    public record LogLineResponse(Instant timestamp, String service, String stream, String text) {}

    public record TemplateListResponse(
            String apiVersion,
            String capability,
            List<TemplateSummaryResponse> templates) {
        public TemplateListResponse {
            templates = List.copyOf(templates);
        }
    }

    public record TemplateSummaryResponse(String id, String name, String stack, String description) {}

    public record SettingsResponse(
            String apiVersion,
            String bindAddress,
            boolean remoteAccess,
            boolean accountRequired,
            boolean telemetryEnabled,
            String templatesCapability,
            String logsCapability,
            String testExecutionCapability) {}
}
