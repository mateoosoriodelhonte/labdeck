package io.labdeck.persistence.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.labdeck.lab.LabFailureCode;
import io.labdeck.lab.LabRecord;
import io.labdeck.lab.LabRuntimeFailure;
import io.labdeck.lab.LabState;
import java.time.Instant;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteLabRuntimeFailureTests {

    private static final Instant CREATED = Instant.parse("2026-08-26T20:00:00Z");

    @TempDir
    java.nio.file.Path temporaryDirectory;

    @Test
    void storesTheFailureWithTheTerminalRevisionAndClearsItOnRetry() {
        java.nio.file.Path dataDirectory = temporaryDirectory.resolve("failure-data");
        try (LockedSQLiteDataSource dataSource = open(dataDirectory)) {
            SQLiteLabRepository labs = new SQLiteLabRepository(dataSource);
            labs.create(lab());
            assertThat(labs.compareAndSetState(
                    "lab-a", 0, LabState.IMPORTED, LabState.STARTING, CREATED.plusSeconds(1))).isTrue();
            assertThat(labs.compareAndSetState(
                    "lab-a", 1, LabState.STARTING, LabState.STOPPING, CREATED.plusSeconds(2))).isTrue();
            Instant failedAt = CREATED.plusSeconds(3);
            LabRuntimeFailure failure = new LabRuntimeFailure(
                    "lab-a",
                    3,
                    LabFailureCode.HEALTHCHECK_UNHEALTHY,
                    Optional.of("database"),
                    failedAt,
                    false);

            assertThat(labs.compareAndSetStateWithFailure(
                    "lab-a", 2, LabState.STOPPING, failedAt, failure)).isTrue();
            assertThat(labs.findById("lab-a").orElseThrow().state()).isEqualTo(LabState.FAILED);
            assertThat(labs.findRuntimeFailure("lab-a")).contains(failure);
        }

        try (LockedSQLiteDataSource dataSource = open(dataDirectory)) {
            SQLiteLabRepository labs = new SQLiteLabRepository(dataSource);
            LabRuntimeFailure reopened = labs.findRuntimeFailure("lab-a").orElseThrow();
            assertThat(reopened.safeMessage())
                    .isEqualTo("Service 'database': the Docker health check failed. "
                            + "Check its health command and logs.");

            assertThat(labs.compareAndSetState(
                    "lab-a", 3, LabState.FAILED, LabState.STARTING, CREATED.plusSeconds(4))).isTrue();
            assertThat(labs.findRuntimeFailure("lab-a")).isEmpty();
        }
    }

    @Test
    void rejectsAFailureThatDoesNotMatchTheAtomicStateChange() {
        try (LockedSQLiteDataSource dataSource = open(temporaryDirectory.resolve("mismatch-data"))) {
            SQLiteLabRepository labs = new SQLiteLabRepository(dataSource);
            labs.create(lab());
            assertThat(labs.compareAndSetState(
                    "lab-a", 0, LabState.IMPORTED, LabState.STARTING, CREATED.plusSeconds(1))).isTrue();
            assertThat(labs.compareAndSetState(
                    "lab-a", 1, LabState.STARTING, LabState.STOPPING, CREATED.plusSeconds(2))).isTrue();
            LabRuntimeFailure wrongRevision = new LabRuntimeFailure(
                    "lab-a",
                    99,
                    LabFailureCode.CONTAINER_EXITED,
                    Optional.of("app"),
                    CREATED.plusSeconds(3),
                    false);

            assertThatThrownBy(() -> labs.compareAndSetStateWithFailure(
                    "lab-a", 2, LabState.STOPPING, CREATED.plusSeconds(3), wrongRevision))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("failed transition");

            assertThat(labs.findById("lab-a").orElseThrow().state()).isEqualTo(LabState.STOPPING);
            assertThat(labs.findRuntimeFailure("lab-a")).isEmpty();
        }
    }

    @Test
    void successfulFailedCleanupRetryClearsTheFailureAcrossReopen() {
        java.nio.file.Path dataDirectory = temporaryDirectory.resolve("cleanup-success-data");
        try (LockedSQLiteDataSource dataSource = open(dataDirectory)) {
            SQLiteLabRepository labs = new SQLiteLabRepository(dataSource);
            labs.create(lab());
            storeInitialFailure(labs);

            assertThat(labs.compareAndSetState(
                    "lab-a", 3, LabState.FAILED, LabState.STOPPING, CREATED.plusSeconds(4))).isTrue();
            assertThat(labs.compareAndSetState(
                    "lab-a", 4, LabState.STOPPING, LabState.STOPPED, CREATED.plusSeconds(5))).isTrue();
            assertThat(labs.findRuntimeFailure("lab-a")).isEmpty();
        }

        try (LockedSQLiteDataSource dataSource = open(dataDirectory)) {
            SQLiteLabRepository labs = new SQLiteLabRepository(dataSource);
            assertThat(labs.findById("lab-a").orElseThrow().state()).isEqualTo(LabState.STOPPED);
            assertThat(labs.findRuntimeFailure("lab-a")).isEmpty();
        }
    }

    @Test
    void failedCleanupRetryReplacesTheFailureAcrossReopen() {
        java.nio.file.Path dataDirectory = temporaryDirectory.resolve("cleanup-failure-data");
        LabRuntimeFailure cleanupFailure = new LabRuntimeFailure(
                "lab-a",
                5,
                LabFailureCode.CLEANUP_INCOMPLETE,
                Optional.empty(),
                CREATED.plusSeconds(5),
                true);
        try (LockedSQLiteDataSource dataSource = open(dataDirectory)) {
            SQLiteLabRepository labs = new SQLiteLabRepository(dataSource);
            labs.create(lab());
            storeInitialFailure(labs);

            assertThat(labs.compareAndSetState(
                    "lab-a", 3, LabState.FAILED, LabState.STOPPING, CREATED.plusSeconds(4))).isTrue();
            assertThat(labs.compareAndSetStateWithFailure(
                    "lab-a", 4, LabState.STOPPING, CREATED.plusSeconds(5), cleanupFailure)).isTrue();
        }

        try (LockedSQLiteDataSource dataSource = open(dataDirectory)) {
            SQLiteLabRepository labs = new SQLiteLabRepository(dataSource);
            assertThat(labs.findById("lab-a").orElseThrow().state()).isEqualTo(LabState.FAILED);
            assertThat(labs.findRuntimeFailure("lab-a")).contains(cleanupFailure);
        }
    }

    @Test
    void upgradesAnExistingV3FailureDatabaseWithoutChangingItsChecksum() {
        java.nio.file.Path dataDirectory = temporaryDirectory.resolve("v3-upgrade-data");
        try (LockedSQLiteDataSource dataSource = openAtV3(dataDirectory)) {
            SQLiteLabRepository labs = new SQLiteLabRepository(dataSource);
            labs.create(lab());
            storeInitialFailure(labs);
            assertThat(labs.findRuntimeFailure("lab-a").orElseThrow().code())
                    .isEqualTo(LabFailureCode.HEALTHCHECK_UNHEALTHY);
        }

        try (LockedSQLiteDataSource dataSource = open(dataDirectory)) {
            SQLiteLabRepository labs = new SQLiteLabRepository(dataSource);
            assertThat(labs.findRuntimeFailure("lab-a").orElseThrow().code())
                    .isEqualTo(LabFailureCode.HEALTHCHECK_UNHEALTHY);
            assertThat(labs.compareAndSetState(
                    "lab-a", 3, LabState.FAILED, LabState.STARTING, CREATED.plusSeconds(4))).isTrue();
            assertThat(labs.compareAndSetState(
                    "lab-a", 4, LabState.STARTING, LabState.STOPPING, CREATED.plusSeconds(5))).isTrue();
            LabRuntimeFailure storageFailure = new LabRuntimeFailure(
                    "lab-a",
                    6,
                    LabFailureCode.DOCKER_STORAGE_FULL,
                    Optional.empty(),
                    CREATED.plusSeconds(6),
                    false);
            assertThat(labs.compareAndSetStateWithFailure(
                    "lab-a", 5, LabState.STOPPING, CREATED.plusSeconds(6), storageFailure)).isTrue();
            assertThat(labs.findRuntimeFailure("lab-a")).contains(storageFailure);
        }
    }

    private static void storeInitialFailure(SQLiteLabRepository labs) {
        assertThat(labs.compareAndSetState(
                "lab-a", 0, LabState.IMPORTED, LabState.STARTING, CREATED.plusSeconds(1))).isTrue();
        assertThat(labs.compareAndSetState(
                "lab-a", 1, LabState.STARTING, LabState.STOPPING, CREATED.plusSeconds(2))).isTrue();
        LabRuntimeFailure initial = new LabRuntimeFailure(
                "lab-a",
                3,
                LabFailureCode.HEALTHCHECK_UNHEALTHY,
                Optional.of("database"),
                CREATED.plusSeconds(3),
                false);
        assertThat(labs.compareAndSetStateWithFailure(
                "lab-a", 2, LabState.STOPPING, CREATED.plusSeconds(3), initial)).isTrue();
    }

    private LabRecord lab() {
        return new LabRecord(
                "lab-a",
                "project-a",
                "Failure lab",
                1,
                temporaryDirectory.resolve("workspace"),
                LabState.IMPORTED,
                0,
                CREATED,
                CREATED);
    }

    private static LockedSQLiteDataSource open(java.nio.file.Path dataDirectory) {
        LockedSQLiteDataSource dataSource = new SQLiteDataSourceFactory().create(dataDirectory);
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
        return dataSource;
    }

    private static LockedSQLiteDataSource openAtV3(java.nio.file.Path dataDirectory) {
        LockedSQLiteDataSource dataSource = new SQLiteDataSourceFactory().create(dataDirectory);
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("3"))
                .cleanDisabled(true)
                .load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
        return dataSource;
    }
}
