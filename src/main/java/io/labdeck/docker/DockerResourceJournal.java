package io.labdeck.docker;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DockerResourceJournal {

    void reserve(DockerResourceRecord resource);

    boolean activate(String ownershipToken, String engineId, Instant updatedAt);

    boolean markRemoved(String ownershipToken, Optional<String> expectedEngineId, Instant updatedAt);

    Optional<DockerResourceRecord> findOpen(
            LabOwnership ownership, DockerResourceType type, String logicalName);

    List<DockerResourceRecord> findOpenByLab(LabOwnership ownership);
}
