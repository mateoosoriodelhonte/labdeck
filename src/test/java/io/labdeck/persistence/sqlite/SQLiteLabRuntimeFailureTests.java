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
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate();
        return dataSource;
    }
}
