package io.labdeck.persistence.sqlite;

import io.labdeck.lab.LabFailureCode;
import io.labdeck.lab.LabRecord;
import io.labdeck.lab.LabRepository;
import io.labdeck.lab.LabRuntimeFailure;
import io.labdeck.lab.LabState;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class SQLiteLabRepository implements LabRepository {

    private static final RowMapper<LabRecord> ROW_MAPPER = SQLiteLabRepository::mapLab;
    private static final RowMapper<LabRuntimeFailure> FAILURE_ROW_MAPPER = SQLiteLabRepository::mapFailure;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public SQLiteLabRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public void create(LabRecord lab) {
        jdbc.update("""
                INSERT INTO lab (
                    id, project_id, name, manifest_version, workspace_path,
                    lifecycle_state, revision, created_at_epoch_ms, updated_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                lab.id(),
                lab.projectId(),
                lab.name(),
                lab.manifestVersion(),
                lab.workspace().toString(),
                lab.state().name(),
                lab.revision(),
                lab.createdAt().toEpochMilli(),
                lab.updatedAt().toEpochMilli());
    }

    @Override
    public Optional<LabRecord> findById(String id) {
        requireId(id);
        List<LabRecord> matches = jdbc.query("""
                SELECT id, project_id, name, manifest_version, workspace_path,
                       lifecycle_state, revision, created_at_epoch_ms, updated_at_epoch_ms
                FROM lab
                WHERE id = ?
                """, ROW_MAPPER, id);
        return matches.stream().findFirst();
    }

    @Override
    public List<LabRecord> findAll() {
        return jdbc.query("""
                SELECT id, project_id, name, manifest_version, workspace_path,
                       lifecycle_state, revision, created_at_epoch_ms, updated_at_epoch_ms
                FROM lab
                ORDER BY updated_at_epoch_ms DESC, id DESC
                """, ROW_MAPPER);
    }

    @Override
    public boolean compareAndSetState(
            String id, long expectedRevision, LabState expected, LabState next, Instant updatedAt) {
        validateTransition(id, expectedRevision, expected, next, updatedAt);
        Boolean changed = transactions.execute(status -> {
            boolean updated = updateState(id, expectedRevision, expected, next, updatedAt);
            if (updated && next == LabState.STARTING) {
                jdbc.update("DELETE FROM lab_runtime_failure WHERE lab_id = ?", id);
            }
            return updated;
        });
        return Boolean.TRUE.equals(changed);
    }

    @Override
    public boolean compareAndSetStateWithFailure(
            String id,
            long expectedRevision,
            LabState expected,
            Instant updatedAt,
            LabRuntimeFailure failure) {
        validateTransition(id, expectedRevision, expected, LabState.FAILED, updatedAt);
        if (failure == null
                || !id.equals(failure.labId())
                || failure.labRevision() != expectedRevision + 1
                || !updatedAt.equals(failure.occurredAt())) {
            throw new IllegalArgumentException("The runtime failure does not match the failed transition.");
        }
        Boolean changed = transactions.execute(status -> {
            if (!updateState(id, expectedRevision, expected, LabState.FAILED, updatedAt)) {
                return false;
            }
            jdbc.update("""
                    INSERT INTO lab_runtime_failure (
                        lab_id, lab_revision, failure_code, service_id,
                        occurred_at_epoch_ms, cleanup_incomplete
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(lab_id) DO UPDATE SET
                        lab_revision = excluded.lab_revision,
                        failure_code = excluded.failure_code,
                        service_id = excluded.service_id,
                        occurred_at_epoch_ms = excluded.occurred_at_epoch_ms,
                        cleanup_incomplete = excluded.cleanup_incomplete
                    """,
                    failure.labId(),
                    failure.labRevision(),
                    failure.code().name(),
                    failure.service().orElse(null),
                    failure.occurredAt().toEpochMilli(),
                    failure.cleanupIncomplete() ? 1 : 0);
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    @Override
    public Optional<LabRuntimeFailure> findRuntimeFailure(String labId) {
        requireId(labId);
        return jdbc.query("""
                SELECT lab_id, lab_revision, failure_code, service_id,
                       occurred_at_epoch_ms, cleanup_incomplete
                FROM lab_runtime_failure
                WHERE lab_id = ?
                """, FAILURE_ROW_MAPPER, labId).stream().findFirst();
    }

    private boolean updateState(
            String id, long expectedRevision, LabState expected, LabState next, Instant updatedAt) {
        return jdbc.update("""
                UPDATE lab
                SET lifecycle_state = ?, revision = revision + 1, updated_at_epoch_ms = ?
                WHERE id = ?
                  AND revision = ?
                  AND lifecycle_state = ?
                  AND updated_at_epoch_ms <= ?
                """,
                next.name(),
                updatedAt.toEpochMilli(),
                id,
                expectedRevision,
                expected.name(),
                updatedAt.toEpochMilli()) == 1;
    }

    private static void validateTransition(
            String id, long expectedRevision, LabState expected, LabState next, Instant updatedAt) {
        requireId(id);
        if (expectedRevision < 0 || expected == null || !expected.canTransitionTo(next)) {
            throw new IllegalArgumentException("The requested lifecycle transition is not allowed.");
        }
        if (updatedAt == null || updatedAt.isBefore(Instant.EPOCH)) {
            throw new IllegalArgumentException("The lifecycle timestamp is not valid.");
        }
        updatedAt.toEpochMilli();
    }

    private static LabRecord mapLab(ResultSet results, int rowNumber) throws SQLException {
        return new LabRecord(
                results.getString("id"),
                results.getString("project_id"),
                results.getString("name"),
                results.getInt("manifest_version"),
                Path.of(results.getString("workspace_path")),
                LabState.valueOf(results.getString("lifecycle_state")),
                results.getLong("revision"),
                Instant.ofEpochMilli(results.getLong("created_at_epoch_ms")),
                Instant.ofEpochMilli(results.getLong("updated_at_epoch_ms")));
    }

    private static LabRuntimeFailure mapFailure(ResultSet results, int rowNumber) throws SQLException {
        return new LabRuntimeFailure(
                results.getString("lab_id"),
                results.getLong("lab_revision"),
                LabFailureCode.valueOf(results.getString("failure_code")),
                Optional.ofNullable(results.getString("service_id")),
                Instant.ofEpochMilli(results.getLong("occurred_at_epoch_ms")),
                results.getInt("cleanup_incomplete") == 1);
    }

    private static void requireId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("The lab ID is not valid.");
        }
    }
}
