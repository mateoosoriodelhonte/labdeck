package io.labdeck.docker;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

public record DockerContainerMetrics(
        OptionalDouble cpuPercent,
        OptionalLong memoryUsageBytes,
        OptionalLong memoryLimitBytes,
        OptionalLong networkReadBytes,
        OptionalLong networkWriteBytes,
        Optional<Instant> startedAt) {

    public static final DockerContainerMetrics UNAVAILABLE = new DockerContainerMetrics(
            OptionalDouble.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            Optional.empty());

    public DockerContainerMetrics {
        cpuPercent = cpuPercent == null ? OptionalDouble.empty() : cpuPercent;
        memoryUsageBytes = memoryUsageBytes == null ? OptionalLong.empty() : memoryUsageBytes;
        memoryLimitBytes = memoryLimitBytes == null ? OptionalLong.empty() : memoryLimitBytes;
        networkReadBytes = networkReadBytes == null ? OptionalLong.empty() : networkReadBytes;
        networkWriteBytes = networkWriteBytes == null ? OptionalLong.empty() : networkWriteBytes;
        startedAt = startedAt == null ? Optional.empty() : startedAt;
        cpuPercent.ifPresent(value -> {
            if (!Double.isFinite(value) || value < 0) {
                throw new IllegalArgumentException("The container CPU percentage is not valid.");
            }
        });
        requireNonNegative(memoryUsageBytes, "memory usage");
        requireNonNegative(memoryLimitBytes, "memory limit");
        requireNonNegative(networkReadBytes, "network read count");
        requireNonNegative(networkWriteBytes, "network write count");
        startedAt.ifPresent(value -> Objects.requireNonNull(value, "startedAt"));
    }

    private static void requireNonNegative(OptionalLong value, String name) {
        if (value.isPresent() && value.orElseThrow() < 0) {
            throw new IllegalArgumentException("The container " + name + " is not valid.");
        }
    }
}
