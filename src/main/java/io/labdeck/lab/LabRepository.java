package io.labdeck.lab;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LabRepository {

    void create(LabRecord lab);

    Optional<LabRecord> findById(String id);

    List<LabRecord> findAll();

    boolean compareAndSetState(
            String id, long expectedRevision, LabState expected, LabState next, Instant updatedAt);

    boolean compareAndSetStateWithFailure(
            String id,
            long expectedRevision,
            LabState expected,
            Instant updatedAt,
            LabRuntimeFailure failure);

    Optional<LabRuntimeFailure> findRuntimeFailure(String labId);
}
