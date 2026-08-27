package io.labdeck.docker;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public interface DockerEnginePort {

    void verifyAvailable();

    void verifyLocalPortPublishingSupported();

    void verifyResourceLimitsSupported();

    Optional<DockerImageMetadata> inspectImage(String reference);

    void pullPublicImageAfterConfirmation(
            String reference, Duration timeout, CancellationToken cancellation);

    Optional<DockerCreatedResource> reconcileDispatched(DockerResourceRecord dispatched);

    DockerCreatedResource createNetwork(DockerResourceRecord dispatched);

    DockerCreatedResource createVolume(DockerResourceRecord dispatched);

    DockerCreatedResource createContainer(
            DockerResourceRecord dispatched, DockerContainerSpec specification);

    DockerContainerView inspectContainer(
            DockerResourceRecord active, DockerContainerSpec specification);

    DockerContainerView inspectContainerSnapshot(DockerResourceRecord active);

    DockerServiceObservation inspectContainerObservation(
            DockerResourceRecord active, Map<String, String> ownedVolumes);

    DockerVolumeObservation inspectVolumeObservation(DockerResourceRecord active);

    void verifyNetwork(DockerResourceRecord active);

    DockerLogBatch readContainerLogs(DockerResourceRecord active, int tail);

    DockerLogSubscription followContainerLogs(
            DockerResourceRecord active, int tail, Consumer<DockerLogLine> consumer);

    void startContainer(DockerResourceRecord active, DockerContainerSpec specification);

    void stopContainer(DockerResourceRecord active, Duration timeout);

    void removeContainer(DockerResourceRecord active);

    void removeNetwork(DockerResourceRecord active);

    void verifyVolume(DockerResourceRecord active);
}
