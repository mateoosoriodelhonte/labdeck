package io.labdeck.docker;

import java.time.Duration;
import java.util.Optional;

public interface DockerEnginePort {

    void verifyAvailable();

    Optional<DockerImageMetadata> inspectImage(String reference);

    void pullPublicImageAfterConfirmation(
            String reference, Duration timeout, CancellationToken cancellation);

    Optional<String> reconcileReserved(DockerResourceRecord reserved);

    String createNetwork(DockerResourceRecord reserved);

    String createVolume(DockerResourceRecord reserved);

    String createContainer(DockerResourceRecord reserved, DockerContainerSpec specification);

    DockerContainerView inspectContainer(DockerResourceRecord active);

    void startContainer(DockerResourceRecord active);

    void stopContainer(DockerResourceRecord active, Duration timeout);

    void removeContainer(DockerResourceRecord active);

    void removeNetwork(DockerResourceRecord active);

    void verifyVolume(DockerResourceRecord active);
}
