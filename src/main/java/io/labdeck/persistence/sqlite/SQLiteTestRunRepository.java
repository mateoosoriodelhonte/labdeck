package io.labdeck.persistence.sqlite;

import io.labdeck.lab.StoredOutput;
import io.labdeck.lab.TestRunRecord;
import io.labdeck.lab.TestRunRepository;
import io.labdeck.lab.TestStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SQLiteTestRunRepository implements TestRunRepository {

    private static final RowMapper<TestRunRecord> ROW_MAPPER = SQLiteTestRunRepository::mapTestRun;
    private final JdbcTemplate jdbc;

    public SQLiteTestRunRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void append(TestRunRecord testRun) {
        if (!testRun.stdout().safeToPersist() || !testRun.stderr().safeToPersist()) {
            throw new IllegalArgumentException("Test output must be scrubbed before persistence.");
        }
        jdbc.update("""
                INSERT INTO test_run (
                    id, lab_id, recorded_at_epoch_ms, status, duration_ms, exit_code,
                    stdout, stderr, stdout_truncated, stderr_truncated
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                testRun.id(),
                testRun.labId(),
                testRun.recordedAt().toEpochMilli(),
                testRun.status().name(),
                testRun.duration().toMillis(),
                testRun.exitCode().isPresent() ? testRun.exitCode().getAsInt() : null,
                testRun.stdout().text(),
                testRun.stderr().text(),
                testRun.stdout().truncated() ? 1 : 0,
                testRun.stderr().truncated() ? 1 : 0);
    }

    @Override
    public Optional<TestRunRecord> findById(String id) {
        requireId(id, "test run");
        List<TestRunRecord> matches = jdbc.query("""
                SELECT id, lab_id, recorded_at_epoch_ms, status, duration_ms, exit_code,
                       stdout, stderr, stdout_truncated, stderr_truncated
                FROM test_run
                WHERE id = ?
                """, ROW_MAPPER, id);
        return matches.stream().findFirst();
    }

    @Override
    public List<TestRunRecord> findRecentByLab(String labId, int limit) {
        requireId(labId, "lab");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("The test history limit must be from 1 to 100.");
        }
        return jdbc.query("""
                SELECT id, lab_id, recorded_at_epoch_ms, status, duration_ms, exit_code,
                       stdout, stderr, stdout_truncated, stderr_truncated
                FROM test_run
                WHERE lab_id = ?
                ORDER BY recorded_at_epoch_ms DESC, id DESC
                LIMIT ?
                """, ROW_MAPPER, labId, limit);
    }

    private static TestRunRecord mapTestRun(ResultSet results, int rowNumber) throws SQLException {
        int exitCodeValue = results.getInt("exit_code");
        OptionalInt exitCode = results.wasNull() ? OptionalInt.empty() : OptionalInt.of(exitCodeValue);
        return new TestRunRecord(
                results.getString("id"),
                results.getString("lab_id"),
                Instant.ofEpochMilli(results.getLong("recorded_at_epoch_ms")),
                TestStatus.valueOf(results.getString("status")),
                Duration.ofMillis(results.getLong("duration_ms")),
                exitCode,
                StoredOutput.fromPersistence(
                        results.getString("stdout"), results.getInt("stdout_truncated") == 1),
                StoredOutput.fromPersistence(
                        results.getString("stderr"), results.getInt("stderr_truncated") == 1));
    }

    private static void requireId(String id, String label) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("The " + label + " ID is not valid.");
        }
    }
}
