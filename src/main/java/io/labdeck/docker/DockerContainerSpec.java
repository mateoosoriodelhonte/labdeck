package io.labdeck.docker;

import io.labdeck.manifest.ApprovedWorkspacePath;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record DockerContainerSpec(
        String image,
        String workingDirectory,
        List<String> command,
        Map<String, String> environment,
        ApprovedWorkspacePath workspace,
        String workspaceTarget,
        String networkId,
        List<NamedMount> namedMounts) {

    public DockerContainerSpec {
        requireText(image, "image", 255);
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
    }

    public List<String> coveredImageVolumeTargets() {
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(workspaceTarget),
                        namedMounts.stream().map(NamedMount::target))
                .sorted()
                .toList();
    }

    @Override
    public String toString() {
        return "DockerContainerSpec[image=" + image
                + ", workingDirectory=" + workingDirectory
                + ", command=" + command
                + ", environmentKeys=" + new java.util.TreeSet<>(environment.keySet())
                + ", workspace=" + workspace
                + ", workspaceTarget=" + workspaceTarget
                + ", networkId=" + networkId
                + ", namedMounts=" + namedMounts + "]";
    }

    public record NamedMount(String volumeId, String target, boolean readOnly) {
        public NamedMount {
            requireText(volumeId, "volume ID", 255);
            requireAbsoluteContainerPath(target, "volume target");
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
}
