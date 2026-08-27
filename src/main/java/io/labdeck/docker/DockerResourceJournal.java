package io.labdeck.docker;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DockerResourceJournal {

    void reserve(DockerResourceRecord resource);

    boolean markDispatched(String ownershipToken, Instant updatedAt);

    boolean activate(
            String ownershipToken,
            String engineId,
            Optional<String> engineIdentity,
            Instant updatedAt);

    boolean discardReservation(String ownershipToken, Instant updatedAt);

    boolean closeDispatchWithoutResource(String ownershipToken, Instant updatedAt);

    boolean markRemoved(String ownershipToken, String expectedEngineId, Instant updatedAt);

    Optional<DockerResourceRecord> findOpen(
            LabOwnership ownership, DockerResourceType type, String logicalName);

    List<DockerResourceRecord> findOpenByLab(LabOwnership ownership);
}
