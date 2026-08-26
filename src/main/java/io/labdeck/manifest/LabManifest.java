package io.labdeck.manifest;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

public record LabManifest(
        int version,
        String name,
        Workspace workspace,
        NavigableMap<String, Service> services,
        ResourceLimits resources,
        Optional<TestDefinition> tests) {

    public LabManifest {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(workspace, "workspace");
        services = immutableSortedMap(services);
        Objects.requireNonNull(resources, "resources");
        tests = tests == null ? Optional.empty() : tests;
    }

    public record Workspace(String mount) {
        public Workspace {
            Objects.requireNonNull(mount, "mount");
        }
    }

    public record Service(
            ServiceSource source,
            String workingDirectory,
            List<String> command,
            NavigableMap<String, String> environment,
            List<Port> ports,
            Optional<HealthCheck> healthcheck,
            List<VolumeMount> volumes) {

        public Service {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(workingDirectory, "workingDirectory");
            command = List.copyOf(command);
            environment = immutableSortedMap(environment);
            ports = List.copyOf(ports);
            healthcheck = healthcheck == null ? Optional.empty() : healthcheck;
            volumes = List.copyOf(volumes);
        }

        @Override
        public String toString() {
            return "Service[source=" + source
                    + ", workingDirectory=" + workingDirectory
                    + ", command=" + command
                    + ", environmentKeys=" + environment.navigableKeySet()
                    + ", ports=" + ports
                    + ", healthcheck=" + healthcheck
                    + ", volumes=" + volumes + "]";
        }
    }

    public sealed interface ServiceSource permits ImageSource, BuildSource {
    }

    public record ImageSource(String reference) implements ServiceSource {
        public ImageSource {
            Objects.requireNonNull(reference, "reference");
        }
    }

    public record BuildSource(String context, String dockerfile) implements ServiceSource {
        public BuildSource {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(dockerfile, "dockerfile");
        }
    }

    public record Port(int container, Optional<Integer> host, String protocol) {
        public Port {
            host = host == null ? Optional.empty() : host;
            Objects.requireNonNull(protocol, "protocol");
        }
    }

    public record HealthCheck(
            List<String> command,
            Duration interval,
            Duration timeout,
            int retries,
            Duration startPeriod) {

        public HealthCheck {
            command = List.copyOf(command);
            Objects.requireNonNull(interval, "interval");
            Objects.requireNonNull(timeout, "timeout");
            Objects.requireNonNull(startPeriod, "startPeriod");
        }
    }

    public record VolumeMount(String name, String target, boolean readOnly) {
        public VolumeMount {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(target, "target");
        }
    }

    public record ResourceLimits(long memoryBytes, BigDecimal cpus) {
        public ResourceLimits {
            Objects.requireNonNull(cpus, "cpus");
            cpus = cpus.stripTrailingZeros();
        }
    }

    public record TestDefinition(String service, List<String> command, Duration timeout) {
        public TestDefinition {
            Objects.requireNonNull(service, "service");
            command = List.copyOf(command);
            Objects.requireNonNull(timeout, "timeout");
        }
    }

    private static <K, V> NavigableMap<K, V> immutableSortedMap(NavigableMap<K, V> source) {
        Objects.requireNonNull(source, "source");
        return java.util.Collections.unmodifiableNavigableMap(new TreeMap<>(source));
    }
}
