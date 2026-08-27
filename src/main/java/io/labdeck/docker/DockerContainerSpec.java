package io.labdeck.docker;

import io.labdeck.manifest.ApprovedWorkspacePath;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record DockerContainerSpec(
        String image,
        String imageReference,
        String workingDirectory,
        List<String> command,
        Map<String, String> environment,
        ApprovedWorkspacePath workspace,
        String workspaceTarget,
        String networkId,
        List<NamedMount> namedMounts,
        List<PublishedPort> publishedPorts,
        ResourceLimits resourceLimits,
        Optional<HealthProbe> healthProbe,
        boolean imageHealthCheckConfigured) {

    public DockerContainerSpec {
        requireText(image, "image", 255);
        requireText(imageReference, "image reference", 255);
        requireAbsoluteContainerPath(workingDirectory, "working directory");
        command = List.copyOf(command);
        if (command.size() > 64 || command.stream().anyMatch(value -> value == null || value.isEmpty())) {
            throw new IllegalArgumentException("The container command is not valid.");
        }
        environment = Map.copyOf(environment);
        Objects.requireNonNull(workspace, "workspace");
        requireAbsoluteContainerPath(workspaceTarget, "workspace target");
        requireText(networkId, "network ID", 255);
        namedMounts = List.copyOf(namedMounts);
        if (namedMounts.stream().map(NamedMount::target).distinct().count() != namedMounts.size()) {
            throw new IllegalArgumentException("Named volume targets must be unique.");
        }
        publishedPorts = List.copyOf(publishedPorts);
        if (publishedPorts.stream().map(PublishedPort::containerPort).distinct().count()
                != publishedPorts.size()) {
            throw new IllegalArgumentException("Published container ports must be unique.");
        }
        if (publishedPorts.stream().flatMap(port -> port.hostPort().stream()).distinct().count()
                != publishedPorts.stream().filter(port -> port.hostPort().isPresent()).count()) {
            throw new IllegalArgumentException("Fixed host ports must be unique in a service.");
        }
        Objects.requireNonNull(resourceLimits, "resourceLimits");
        healthProbe = healthProbe == null ? Optional.empty() : healthProbe;
    }

    public List<String> coveredImageVolumeTargets() {
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(workspaceTarget),
                        namedMounts.stream().map(NamedMount::target))
                .sorted()
                .toList();
    }

    public boolean healthCheckRequired() {
        return healthProbe.isPresent() || imageHealthCheckConfigured;
    }

    @Override
    public String toString() {
        return "DockerContainerSpec[image=" + image
                + ", imageReference=" + imageReference
                + ", workingDirectory=" + workingDirectory
                + ", commandItems=" + command.size()
                + ", environmentKeys=" + new java.util.TreeSet<>(environment.keySet())
                + ", workspace=" + workspace
                + ", workspaceTarget=" + workspaceTarget
                + ", networkId=" + networkId
                + ", namedMounts=" + namedMounts
                + ", publishedPorts=" + publishedPorts
                + ", resourceLimits=" + resourceLimits
                + ", healthCheckRequired=" + healthCheckRequired() + "]";
    }

    public record NamedMount(String volumeId, String target, boolean readOnly) {
        public NamedMount {
            requireText(volumeId, "volume ID", 255);
            requireAbsoluteContainerPath(target, "volume target");
        }
    }

    public record PublishedPort(int containerPort, Optional<Integer> hostPort, String protocol) {
        public PublishedPort {
            if (containerPort < 1 || containerPort > 65_535) {
                throw new IllegalArgumentException("The published container port is not valid.");
            }
            hostPort = hostPort == null ? Optional.empty() : hostPort;
            if (hostPort.isPresent() && (hostPort.orElseThrow() < 1_024 || hostPort.orElseThrow() > 65_535)) {
                throw new IllegalArgumentException("The fixed host port is not valid.");
            }
            if (!"tcp".equals(protocol)) {
                throw new IllegalArgumentException("Only TCP ports can be published.");
            }
        }
    }

    public record ResourceLimits(long memoryBytes, long nanoCpus) {
        private static final long MIN_MEMORY_BYTES = 6L * 1_024 * 1_024;
        private static final long MAX_MEMORY_BYTES = 8L * 1_024 * 1_024 * 1_024;
        private static final long MIN_NANO_CPUS = 10_000_000L;
        private static final long MAX_NANO_CPUS = 8_000_000_000L;

        public ResourceLimits {
            if (memoryBytes < MIN_MEMORY_BYTES || memoryBytes > MAX_MEMORY_BYTES) {
                throw new IllegalArgumentException("The container memory limit is not valid.");
            }
            if (nanoCpus < MIN_NANO_CPUS || nanoCpus > MAX_NANO_CPUS) {
                throw new IllegalArgumentException("The container CPU limit is not valid.");
            }
        }
    }

    public record HealthProbe(
            List<String> command,
            Duration interval,
            Duration timeout,
            int retries,
            Duration startPeriod) {

        public HealthProbe {
            command = List.copyOf(command);
            if (command.isEmpty() || command.size() > 64
                    || command.stream().anyMatch(value -> value == null || value.isEmpty())) {
                throw new IllegalArgumentException("The health command is not valid.");
            }
            requireDuration(interval, Duration.ofSeconds(1), Duration.ofMinutes(5), "interval");
            requireDuration(timeout, Duration.ofSeconds(1), Duration.ofMinutes(1), "timeout");
            requireDuration(startPeriod, Duration.ZERO, Duration.ofMinutes(10), "start period");
            if (retries < 1 || retries > 20) {
                throw new IllegalArgumentException("The health retry count is not valid.");
            }
        }

        public Duration readinessBudget() {
            try {
                return startPeriod
                        .plus(interval.multipliedBy((long) retries + 1))
                        .plus(timeout.multipliedBy(retries));
            } catch (ArithmeticException exception) {
                throw new IllegalStateException("The health readiness budget is too large.", exception);
            }
        }

        @Override
        public String toString() {
            return "HealthProbe[commandItems=" + command.size()
                    + ", interval=" + interval
                    + ", timeout=" + timeout
                    + ", retries=" + retries
                    + ", startPeriod=" + startPeriod + "]";
        }
    }

    private static void requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength || !value.equals(value.strip())) {
            throw new IllegalArgumentException("The " + name + " is not valid.");
        }
    }

    private static void requireAbsoluteContainerPath(String value, String name) {
        requireText(value, name, 256);
        if (!value.startsWith("/") || value.contains("//") || value.contains("/../") || value.endsWith("/..")) {
            throw new IllegalArgumentException("The " + name + " is not a safe absolute container path.");
        }
    }

    private static void requireDuration(
            Duration value, Duration minimum, Duration maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("The health " + name + " is not valid.");
        }
    }
}
