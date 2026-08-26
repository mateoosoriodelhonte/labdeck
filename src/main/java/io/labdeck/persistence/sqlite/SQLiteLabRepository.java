package io.labdeck.persistence.sqlite;

import io.labdeck.lab.LabRecord;
import io.labdeck.lab.LabRepository;
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
import org.springframework.stereotype.Repository;

@Repository
public class SQLiteLabRepository implements LabRepository {

    private static final RowMapper<LabRecord> ROW_MAPPER = SQLiteLabRepository::mapLab;
    private final JdbcTemplate jdbc;

    public SQLiteLabRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
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
        requireId(id);
        if (expectedRevision < 0 || expected == null || !expected.canTransitionTo(next)) {
            throw new IllegalArgumentException("The requested lifecycle transition is not allowed.");
        }
        if (updatedAt == null || updatedAt.isBefore(Instant.EPOCH)) {
            throw new IllegalArgumentException("The lifecycle timestamp is not valid.");
        }
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

    private static void requireId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("The lab ID is not valid.");
        }
    }
}
