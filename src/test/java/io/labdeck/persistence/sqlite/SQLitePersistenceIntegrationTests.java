package io.labdeck.persistence.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.labdeck.lab.LabRecord;
import io.labdeck.lab.LabState;
import io.labdeck.lab.StoredOutput;
import io.labdeck.lab.TestRunRecord;
import io.labdeck.lab.TestStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class SQLitePersistenceIntegrationTests {

    private static final Instant CREATED_AT = Instant.parse("2026-08-26T18:00:00Z");
    private static final Instant TESTED_AT = Instant.parse("2026-08-26T18:05:00Z");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    @TempDir
    Path temporaryDirectory;

    @Test
    void migratesPersistsAndReopensTheSameDatabase() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("data");
        Path workspace = temporaryDirectory.resolve("workspace");
        Files.createDirectories(workspace);
        LabRecord lab = lab(workspace, "Robert'); DROP TABLE lab;--");
        TestRunRecord first = result("run-a", TestStatus.PASSED, OptionalInt.of(0), "tests passed", "");
        TestRunRecord second = result(
                "run-b",
                TestStatus.ERROR,
                OptionalInt.empty(),
                "safe output",
                "🙂".repeat(20_000));

        try (LockedSQLiteDataSource dataSource = openAndMigrate(dataDirectory)) {
            SQLiteLabRepository labs = new SQLiteLabRepository(dataSource);
            SQLiteTestRunRepository tests = new SQLiteTestRunRepository(dataSource);
            labs.create(lab);
            tests.append(first);
            tests.append(second);

            assertThat(labs.compareAndSetState(
                            lab.id(), 0, LabState.IMPORTED, LabState.STARTING, CREATED_AT.plusSeconds(10)))
                    .isTrue();
            assertThat(labs.compareAndSetState(
                            lab.id(), 0, LabState.IMPORTED, LabState.STARTING, CREATED_AT.plusSeconds(20)))
                    .isFalse();
        }

        try (LockedSQLiteDataSource dataSource = openAndMigrate(dataDirectory)) {
            SQLiteLabRepository labs = new SQLiteLabRepository(dataSource);
            SQLiteTestRunRepository tests = new SQLiteTestRunRepository(dataSource);

            LabRecord reopened = labs.findById(lab.id()).orElseThrow();
            assertThat(reopened.name()).isEqualTo(lab.name());
            assertThat(reopened.workspace()).isEqualTo(workspace.toAbsolutePath());
            assertThat(reopened.state()).isEqualTo(LabState.STARTING);
            assertThat(reopened.revision()).isEqualTo(1);
            assertThat(tests.findRecentByLab(lab.id(), 100)).containsExactly(second, first);
            assertThat(tests.findById(second.id())).contains(second);

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("PRAGMA quick_check", String.class)).isEqualTo("ok");
            assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
            assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualTo("delete");
        }
    }

    @Test
    void enforcesSchemaOutputAndReferenceConstraints() {
        Path dataDirectory = temporaryDirectory.resolve("constraints");
        Path workspace = temporaryDirectory.resolve("workspace");

        try (LockedSQLiteDataSource dataSource = openAndMigrate(dataDirectory)) {
            SQLiteLabRepository labs = new SQLiteLabRepository(dataSource);
            labs.create(lab(workspace, "Constraint lab"));
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            assertThatThrownBy(() -> insertRawTestRun(
                            jdbc, "orphan", "missing-lab", "ERROR", null, "", ""))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> insertRawTestRun(
                            jdbc,
                            "oversized",
                            "lab-1",
                            "ERROR",
                            null,
                            "x".repeat(StoredOutput.MAX_UTF8_BYTES + 1),
                            ""))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> insertRawTestRun(
                            jdbc, "bad-status", "lab-1", "OFFICIAL_GRADE", 0, "", ""))
                    .isInstanceOf(DataAccessException.class);

            new SQLiteTestRunRepository(dataSource).append(result(
                    "kept-history", TestStatus.PASSED, OptionalInt.of(0), "ok", ""));
            assertThatThrownBy(() -> jdbc.update("DELETE FROM lab WHERE id = ?", "lab-1"))
                    .isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM test_run", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void storesOnlyTheAllowlistedLocalMetadata() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("privacy");
        Path workspace = temporaryDirectory.resolve("workspace");
        Files.createDirectories(workspace);
        String sourceSentinel = "SOURCE-CONTENT-MUST-NOT-ENTER-SQLITE-91c743";
        String credentialSentinel = "CREDENTIAL-MUST-NOT-ENTER-SQLITE-0f6e1a";
        Files.writeString(workspace.resolve("StudentSource.java"), sourceSentinel);

        try (LockedSQLiteDataSource dataSource = openAndMigrate(dataDirectory)) {
            new SQLiteLabRepository(dataSource).create(lab(workspace, "Privacy lab"));
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            assertThat(columnNames(jdbc, "lab")).containsExactly(
                    "id",
                    "project_id",
                    "name",
                    "manifest_version",
                    "workspace_path",
                    "lifecycle_state",
                    "revision",
                    "created_at_epoch_ms",
                    "updated_at_epoch_ms");
            assertThat(columnNames(jdbc, "test_run")).containsExactly(
                    "id",
                    "lab_id",
                    "recorded_at_epoch_ms",
                    "status",
                    "duration_ms",
                    "exit_code",
                    "stdout",
                    "stderr",
                    "stdout_truncated",
                    "stderr_truncated");
        }

        String databaseBytes = Files.readString(
                dataDirectory.resolve(SQLiteDataSourceFactory.DATABASE_FILENAME),
                StandardCharsets.ISO_8859_1);
        assertThat(databaseBytes).doesNotContain(sourceSentinel, credentialSentinel);
        assertThat(databaseBytes.toLowerCase())
                .doesNotContain("manifest_sha", "source_code", "credential", "analytics", "telemetry");
    }

    @Test
    void holdsOneProcessLockAndUsesOwnerOnlyPosixPermissions() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("locked");
        SQLiteDataSourceFactory factory = new SQLiteDataSourceFactory();

        try (LockedSQLiteDataSource ignored = factory.create(dataDirectory)) {
            assertThatThrownBy(() -> factory.create(dataDirectory))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Another LabDeck process");

            FileStore fileStore = Files.getFileStore(dataDirectory);
            if (fileStore.supportsFileAttributeView("posix")) {
                assertThat(Files.getPosixFilePermissions(dataDirectory)).isEqualTo(DIRECTORY_PERMISSIONS);
                assertThat(Files.getPosixFilePermissions(
                                dataDirectory.resolve(SQLiteDataSourceFactory.DATABASE_FILENAME)))
                        .isEqualTo(FILE_PERMISSIONS);
                assertThat(Files.getPosixFilePermissions(
                                dataDirectory.resolve(SQLiteDataSourceFactory.LOCK_FILENAME)))
                        .isEqualTo(FILE_PERMISSIONS);
            }
        }

        try (LockedSQLiteDataSource ignored = factory.create(dataDirectory)) {
            assertThat(ignored).isNotNull();
        }
    }

    private LockedSQLiteDataSource openAndMigrate(Path dataDirectory) {
        LockedSQLiteDataSource dataSource = new SQLiteDataSourceFactory().create(dataDirectory);
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(false)
                    .cleanDisabled(true)
                    .validateOnMigrate(true)
                    .load();
            flyway.migrate();
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
            return dataSource;
        } catch (RuntimeException | AssertionError exception) {
            dataSource.close();
            throw exception;
        }
    }

    private static LabRecord lab(Path workspace, String name) {
        return new LabRecord(
                "lab-1",
                "project-1",
                name,
                1,
                workspace,
                LabState.IMPORTED,
                0,
                CREATED_AT,
                CREATED_AT);
    }

    private static TestRunRecord result(
            String id,
            TestStatus status,
            OptionalInt exitCode,
            String stdout,
            String stderr) {
        return TestRunRecord.bounded(
                id,
                "lab-1",
                TESTED_AT,
                status,
                Duration.ofSeconds(3),
                exitCode,
                stdout,
                stderr);
    }

    private static void insertRawTestRun(
            JdbcTemplate jdbc,
            String id,
            String labId,
            String status,
            Integer exitCode,
            String stdout,
            String stderr) {
        jdbc.update("""
                INSERT INTO test_run (
                    id, lab_id, recorded_at_epoch_ms, status, duration_ms, exit_code,
                    stdout, stderr, stdout_truncated, stderr_truncated
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0)
                """, id, labId, TESTED_AT.toEpochMilli(), status, 0, exitCode, stdout, stderr);
    }

    private static List<String> columnNames(JdbcTemplate jdbc, String table) {
        return jdbc.query("PRAGMA table_info(" + table + ")", (result, rowNumber) -> result.getString("name"));
    }
}
