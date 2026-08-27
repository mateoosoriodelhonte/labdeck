package io.labdeck.docker;

public record DockerPortMapping(
        int containerPort, String hostAddress, int hostPort, String protocol) {

    public DockerPortMapping {
        if (containerPort < 1 || containerPort > 65_535
                || hostPort < 1 || hostPort > 65_535
                || !"127.0.0.1".equals(hostAddress)
                || !"tcp".equals(protocol)) {
            throw new IllegalArgumentException("The Docker port mapping is not safe.");
        }
    }
}
