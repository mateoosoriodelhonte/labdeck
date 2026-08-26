package io.labdeck.manifest;

import io.labdeck.manifest.LabManifest.BuildSource;
import io.labdeck.manifest.LabManifest.ResourceLimits;
import io.labdeck.manifest.LabManifest.Service;
import io.labdeck.manifest.LabManifest.TestDefinition;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ManifestPlan(
        int schemaVersion,
        String manifestSha256,
        String name,
        String workspaceMount,
        ResourceLimits resources,
        List<ServicePlan> services,
        List<String> images,
        List<BuildPlan> builds,
        List<String> volumes,
        Optional<TestDefinition> tests) {

    public ManifestPlan {
        Objects.requireNonNull(manifestSha256, "manifestSha256");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(workspaceMount, "workspaceMount");
        Objects.requireNonNull(resources, "resources");
        services = List.copyOf(services);
        images = List.copyOf(images);
        builds = List.copyOf(builds);
        volumes = List.copyOf(volumes);
        tests = tests == null ? Optional.empty() : tests;
    }

    public record ServicePlan(String id, Service definition) {
        public ServicePlan {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(definition, "definition");
        }
    }

    public record BuildPlan(String service, BuildSource source) {
        public BuildPlan {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(source, "source");
        }
    }
}
