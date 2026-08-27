package io.labdeck.persistence.sqlite;

import io.labdeck.docker.DockerResourceJournal;
import io.labdeck.docker.DockerResourceRecord;
import io.labdeck.docker.DockerResourceState;
import io.labdeck.docker.DockerResourceType;
import io.labdeck.docker.LabOwnership;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SQLiteDockerResourceJournal implements DockerResourceJournal {

    private static final RowMapper<DockerResourceRecord> ROW_MAPPER = SQLiteDockerResourceJournal::map;
    private final JdbcTemplate jdbc;

    public SQLiteDockerResourceJournal(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void reserve(DockerResourceRecord resource) {
        if (resource.state() != DockerResourceState.RESERVED || resource.engineId().isPresent()) {
            throw new IllegalArgumentException("Only a new reserved Docker resource can be stored.");
        }
        jdbc.update("""
                INSERT INTO docker_resource (
                    ownership_token, lab_id, project_id, resource_type, logical_name,
                    engine_id, lifecycle_state, created_at_epoch_ms, updated_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?, NULL, 'RESERVED', ?, ?)
                """,
                resource.ownershipToken(),
                resource.ownership().labId(),
                resource.ownership().projectId(),
                resource.type().name(),
                resource.logicalName(),
                resource.createdAt().toEpochMilli(),
                resource.updatedAt().toEpochMilli());
    }

    @Override
    public boolean markDispatched(String ownershipToken, Instant updatedAt) {
        DockerResourceRecord.requireOwnershipToken(ownershipToken);
        requireTimestamp(updatedAt);
        return jdbc.update("""
                UPDATE docker_resource
                SET lifecycle_state = 'DISPATCHED', updated_at_epoch_ms = ?
                WHERE ownership_token = ?
                  AND lifecycle_state = 'RESERVED'
                  AND engine_id IS NULL
                  AND engine_identity IS NULL
                  AND updated_at_epoch_ms <= ?
                """,
                updatedAt.toEpochMilli(), ownershipToken, updatedAt.toEpochMilli()) == 1;
    }

    @Override
    public boolean activate(
            String ownershipToken,
            String engineId,
            Optional<String> engineIdentity,
            Instant updatedAt) {
        DockerResourceRecord.requireOwnershipToken(ownershipToken);
        requireEngineId(engineId);
        engineIdentity = engineIdentity == null ? Optional.empty() : engineIdentity;
        engineIdentity.ifPresent(SQLiteDockerResourceJournal::requireEngineIdentity);
        requireTimestamp(updatedAt);
        return jdbc.update("""
                UPDATE docker_resource
                SET engine_id = ?, engine_identity = ?, lifecycle_state = 'ACTIVE', updated_at_epoch_ms = ?
                WHERE ownership_token = ?
                  AND lifecycle_state = 'DISPATCHED'
                  AND engine_id IS NULL
                  AND engine_identity IS NULL
                  AND updated_at_epoch_ms <= ?
                """,
                engineId, engineIdentity.orElse(null), updatedAt.toEpochMilli(), ownershipToken,
                updatedAt.toEpochMilli()) == 1;
    }

    @Override
    public boolean discardReservation(String ownershipToken, Instant updatedAt) {
        return discardPending(ownershipToken, DockerResourceState.RESERVED, updatedAt);
    }

    @Override
    public boolean closeDispatchWithoutResource(String ownershipToken, Instant updatedAt) {
        return discardPending(ownershipToken, DockerResourceState.DISPATCHED, updatedAt);
    }

    @Override
    public boolean markRemoved(String ownershipToken, String expectedEngineId, Instant updatedAt) {
        DockerResourceRecord.requireOwnershipToken(ownershipToken);
        requireEngineId(expectedEngineId);
        requireTimestamp(updatedAt);
        return jdbc.update("""
                UPDATE docker_resource
                SET lifecycle_state = 'REMOVED', updated_at_epoch_ms = ?
                WHERE ownership_token = ?
                  AND lifecycle_state = 'ACTIVE'
                  AND engine_id = ?
                  AND updated_at_epoch_ms <= ?
                """,
                updatedAt.toEpochMilli(), ownershipToken, expectedEngineId,
                updatedAt.toEpochMilli()) == 1;
    }

    @Override
    public Optional<DockerResourceRecord> findOpen(
            LabOwnership ownership, DockerResourceType type, String logicalName) {
        if (ownership == null || type == null) {
            throw new IllegalArgumentException("Docker ownership and type are required.");
        }
        DockerResourceRecord.requireLogicalName(logicalName);
        List<DockerResourceRecord> matches = jdbc.query("""
                SELECT ownership_token, lab_id, project_id, resource_type, logical_name,
                       engine_id, engine_identity, lifecycle_state,
                       created_at_epoch_ms, updated_at_epoch_ms
                FROM docker_resource
                WHERE lab_id = ? AND project_id = ? AND resource_type = ? AND logical_name = ?
                  AND lifecycle_state IN ('RESERVED', 'DISPATCHED', 'ACTIVE')
                """, ROW_MAPPER, ownership.labId(), ownership.projectId(), type.name(), logicalName);
        if (matches.size() > 1) {
            throw new IllegalStateException("The Docker resource journal has ambiguous open records.");
        }
        return matches.stream().findFirst();
    }

    @Override
    public List<DockerResourceRecord> findOpenByLab(LabOwnership ownership) {
        if (ownership == null) {
            throw new IllegalArgumentException("Docker ownership is required.");
        }
        return jdbc.query("""
                SELECT ownership_token, lab_id, project_id, resource_type, logical_name,
                       engine_id, engine_identity, lifecycle_state,
                       created_at_epoch_ms, updated_at_epoch_ms
                FROM docker_resource
                WHERE lab_id = ? AND project_id = ?
                  AND lifecycle_state IN ('RESERVED', 'DISPATCHED', 'ACTIVE')
                ORDER BY CASE resource_type
                    WHEN 'CONTAINER' THEN 1 WHEN 'NETWORK' THEN 2 ELSE 3 END,
                    logical_name, ownership_token
                """, ROW_MAPPER, ownership.labId(), ownership.projectId());
    }

    private static DockerResourceRecord map(ResultSet results, int rowNumber) throws SQLException {
        String engineId = results.getString("engine_id");
        return new DockerResourceRecord(
                results.getString("ownership_token"),
                new LabOwnership(results.getString("lab_id"), results.getString("project_id")),
                DockerResourceType.valueOf(results.getString("resource_type")),
                results.getString("logical_name"),
                Optional.ofNullable(engineId),
                Optional.ofNullable(results.getString("engine_identity")),
                DockerResourceState.valueOf(results.getString("lifecycle_state")),
                Instant.ofEpochMilli(results.getLong("created_at_epoch_ms")),
                Instant.ofEpochMilli(results.getLong("updated_at_epoch_ms")));
    }

    private static void requireEngineId(String engineId) {
        if (engineId == null || engineId.isBlank() || engineId.length() > 255
                || !engineId.equals(engineId.strip())) {
            throw new IllegalArgumentException("The Docker engine ID is not valid.");
        }
    }

    private static void requireEngineIdentity(String identity) {
        if (identity == null || identity.isBlank() || identity.length() > 255
                || !identity.equals(identity.strip())
                || identity.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("The Docker Engine identity is not valid.");
        }
    }

    private boolean discardPending(
            String ownershipToken, DockerResourceState expectedState, Instant updatedAt) {
        DockerResourceRecord.requireOwnershipToken(ownershipToken);
        if (expectedState != DockerResourceState.RESERVED
                && expectedState != DockerResourceState.DISPATCHED) {
            throw new IllegalArgumentException("A pending Docker resource state is required.");
        }
        requireTimestamp(updatedAt);
        return jdbc.update("""
                UPDATE docker_resource
                SET lifecycle_state = 'REMOVED', updated_at_epoch_ms = ?
                WHERE ownership_token = ?
                  AND lifecycle_state = ?
                  AND engine_id IS NULL
                  AND engine_identity IS NULL
                  AND updated_at_epoch_ms <= ?
                """,
                updatedAt.toEpochMilli(), ownershipToken, expectedState.name(),
                updatedAt.toEpochMilli()) == 1;
    }

    private static void requireTimestamp(Instant updatedAt) {
        if (updatedAt == null || updatedAt.isBefore(Instant.EPOCH)) {
            throw new IllegalArgumentException("The Docker resource timestamp is not valid.");
        }
        try {
            updatedAt.toEpochMilli();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("The Docker resource timestamp is outside the storage range.", exception);
        }
    }
}
