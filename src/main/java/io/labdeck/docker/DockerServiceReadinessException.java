package io.labdeck.docker;

import java.util.List;
import java.util.OptionalInt;

public final class DockerServiceReadinessException extends IllegalStateException {

    public enum Reason {
        EXITED,
        UNHEALTHY,
        HEALTH_NOT_REPORTED,
        TIMED_OUT
    }

    private final Reason reason;
    private final List<String> services;
    private final OptionalInt exitCode;

    private DockerServiceReadinessException(
            Reason reason, List<String> services, OptionalInt exitCode, String message) {
        super(message);
        this.reason = reason;
        this.services = List.copyOf(services);
        this.exitCode = exitCode;
    }

    static DockerServiceReadinessException exited(String service, OptionalInt exitCode) {
        String code = exitCode.isPresent() ? Integer.toString(exitCode.getAsInt()) : "unknown";
        return new DockerServiceReadinessException(
                Reason.EXITED,
                List.of(service),
                exitCode,
                "Service '" + service + "' exited during startup with code " + code
                        + ". Check its command and logs.");
    }

    static DockerServiceReadinessException unhealthy(String service) {
        return new DockerServiceReadinessException(
                Reason.UNHEALTHY,
                List.of(service),
                OptionalInt.empty(),
                "Service '" + service + "' reported unhealthy. Check its health command and logs.");
    }

    static DockerServiceReadinessException healthNotReported(String service) {
        return new DockerServiceReadinessException(
                Reason.HEALTH_NOT_REPORTED,
                List.of(service),
                OptionalInt.empty(),
                "Service '" + service
                        + "' did not report Docker health. Check the local Docker engine and health command.");
    }

    static DockerServiceReadinessException timedOut(List<String> services) {
        List<String> ordered = services.stream().distinct().sorted().toList();
        return new DockerServiceReadinessException(
                Reason.TIMED_OUT,
                ordered,
                OptionalInt.empty(),
                "Services did not become healthy before the startup deadline: "
                        + String.join(", ", ordered) + ". Check their health commands and logs.");
    }

    public Reason reason() {
        return reason;
    }

    public List<String> services() {
        return services;
    }

    public OptionalInt exitCode() {
        return exitCode;
    }
}
