package io.labdeck.manifest;

import io.labdeck.manifest.LabManifest.BuildSource;
import io.labdeck.manifest.LabManifest.HealthCheck;
import io.labdeck.manifest.LabManifest.ImageSource;
import io.labdeck.manifest.LabManifest.Port;
import io.labdeck.manifest.LabManifest.Service;
import io.labdeck.manifest.LabManifest.TestDefinition;
import io.labdeck.manifest.LabManifest.VolumeMount;
import io.labdeck.manifest.ManifestPlan.BuildPlan;
import io.labdeck.manifest.ManifestPlan.ServicePlan;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

public final class ManifestPlanCompiler {

    public ManifestPlan compile(LabManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        List<ServicePlan> services = new ArrayList<>();
        List<BuildPlan> builds = new ArrayList<>();
        TreeSet<String> images = new TreeSet<>();
        TreeSet<String> volumes = new TreeSet<>();

        manifest.services().forEach((id, service) -> {
            services.add(new ServicePlan(id, service));
            if (service.source() instanceof ImageSource image) {
                images.add(image.reference());
            } else if (service.source() instanceof BuildSource build) {
                builds.add(new BuildPlan(id, build));
            }
            service.volumes().stream().map(VolumeMount::name).forEach(volumes::add);
        });

        return new ManifestPlan(
                manifest.version(),
                fingerprint(manifest),
                manifest.name(),
                manifest.workspace().mount(),
                manifest.resources(),
                services,
                List.copyOf(images),
                builds,
                List.copyOf(volumes),
                manifest.tests());
    }

    private static String fingerprint(LabManifest manifest) {
        CanonicalText canonical = new CanonicalText();
        canonical.value(Integer.toString(manifest.version()));
        canonical.value(manifest.name());
        canonical.value(manifest.workspace().mount());
        canonical.value(Long.toString(manifest.resources().memoryBytes()));
        canonical.value(manifest.resources().cpus().toPlainString());
        canonical.value(Integer.toString(manifest.services().size()));
        for (Map.Entry<String, Service> entry : manifest.services().entrySet()) {
            canonical.value(entry.getKey());
            appendService(canonical, entry.getValue());
        }
        canonical.value(manifest.tests().isPresent() ? "1" : "0");
        manifest.tests().ifPresent(test -> appendTest(canonical, test));

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", exception);
        }
    }

    private static void appendService(CanonicalText canonical, Service service) {
        if (service.source() instanceof ImageSource image) {
            canonical.value("image");
            canonical.value(image.reference());
        } else if (service.source() instanceof BuildSource build) {
            canonical.value("build");
            canonical.value(build.context());
            canonical.value(build.dockerfile());
        }
        canonical.value(service.workingDirectory());
        canonical.list(service.command());
        canonical.value(Integer.toString(service.environment().size()));
        service.environment().forEach((key, value) -> {
            canonical.value(key);
            canonical.value(value);
        });
        canonical.value(Integer.toString(service.ports().size()));
        for (Port port : service.ports()) {
            canonical.value(Integer.toString(port.container()));
            canonical.value(port.host().map(String::valueOf).orElse("dynamic"));
            canonical.value(port.protocol());
        }
        canonical.value(service.healthcheck().isPresent() ? "1" : "0");
        service.healthcheck().ifPresent(health -> appendHealthCheck(canonical, health));
        canonical.value(Integer.toString(service.volumes().size()));
        for (VolumeMount volume : service.volumes()) {
            canonical.value(volume.name());
            canonical.value(volume.target());
            canonical.value(Boolean.toString(volume.readOnly()));
        }
    }

    private static void appendHealthCheck(CanonicalText canonical, HealthCheck health) {
        canonical.list(health.command());
        canonical.value(Long.toString(health.interval().toMillis()));
        canonical.value(Long.toString(health.timeout().toMillis()));
        canonical.value(Integer.toString(health.retries()));
        canonical.value(Long.toString(health.startPeriod().toMillis()));
    }

    private static void appendTest(CanonicalText canonical, TestDefinition test) {
        canonical.value(test.service());
        canonical.list(test.command());
        canonical.value(Long.toString(test.timeout().toMillis()));
    }

    private static final class CanonicalText {
        private final StringBuilder value = new StringBuilder();

        void value(String item) {
            value.append(item.length()).append(':').append(item).append(';');
        }

        void list(List<String> items) {
            value(Integer.toString(items.size()));
            items.forEach(this::value);
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }
}
