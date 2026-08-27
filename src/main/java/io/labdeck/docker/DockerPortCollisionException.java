package io.labdeck.docker;

import java.util.List;

public final class DockerPortCollisionException extends IllegalStateException {

    private final String service;
    private final List<Integer> hostPorts;

    DockerPortCollisionException(String service, List<Integer> hostPorts, Throwable cause) {
        super(message(service, hostPorts), cause);
        this.service = service;
        this.hostPorts = List.copyOf(hostPorts);
    }

    public String service() {
        return service;
    }

    public List<Integer> hostPorts() {
        return hostPorts;
    }

    private static String message(String service, List<Integer> hostPorts) {
        String ports = hostPorts.stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(", "));
        return "Service '" + service + "' could not use local port " + ports
                + ". Stop the other app or choose a different host port.";
    }
}
