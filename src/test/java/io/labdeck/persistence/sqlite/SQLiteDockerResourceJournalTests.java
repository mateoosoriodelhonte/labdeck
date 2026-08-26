package io.labdeck.persistence.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.labdeck.docker.DockerResourceRecord;
import io.labdeck.docker.DockerResourceState;
import io.labdeck.docker.DockerResourceType;
import io.labdeck.docker.LabOwnership;
import io.labdeck.lab.LabRecord;
import io.labdeck.lab.LabState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;

class SQLiteDockerResourceJournalTests {

    private static final Instant CREATED = Instant.parse("2026-08-26T20:00:00Z");
    private static final LabOwnership OWNERSHIP = new LabOwnership("lab-a", "project-a");

    @TempDir
    Path temporaryDirectory;

    @Test
    void journalsReservationActivationRemovalAndPersistentVolumeReuse() {
        try (LockedSQLiteDataSource dataSource = open()) {
            SQLiteLabRepository labs = new SQLiteLabRepository(dataSource);
            labs.create(new LabRecord(
                    "lab-a", "project-a", "Journal lab", 1,
                    temporaryDirectory.resolve("workspace"), LabState.IMPORTED, 0, CREATED, CREATED));
            SQLiteDockerResourceJournal journal = new SQLiteDockerResourceJournal(dataSource);
            DockerResourceRecord reserved = DockerResourceRecord.reserved(
                    "0123456789abcdef0123456789abcdef", OWNERSHIP,
                    DockerResourceType.VOLUME, "course-data", CREATED);

            journal.reserve(reserved);
            assertThat(journal.findOpen(OWNERSHIP, DockerResourceType.VOLUME, "course-data"))
                    .contains(reserved);
            assertThat(journal.activate(
                    reserved.ownershipToken(), "labdeck-volume-id", CREATED.plusSeconds(1))).isTrue();
            DockerResourceRecord active = journal.findOpenByLab(OWNERSHIP).getFirst();
            assertThat(active.state()).isEqualTo(DockerResourceState.ACTIVE);
            assertThat(active.engineId()).contains("labdeck-volume-id");

            assertThat(journal.markRemoved(
                    active.ownershipToken(), Optional.of("wrong-id"), CREATED.plusSeconds(2))).isFalse();
            assertThat(journal.markRemoved(
                    active.ownershipToken(), active.engineId(), CREATED.plusSeconds(2))).isTrue();
            assertThat(journal.findOpenByLab(OWNERSHIP)).isEmpty();
        }
    }

    @Test
    void rejectsTwoOpenRecordsForTheSameLogicalResource() {
        try (LockedSQLiteDataSource dataSource = open()) {
            new SQLiteLabRepository(dataSource).create(new LabRecord(
                    "lab-a", "project-a", "Journal lab", 1,
                    temporaryDirectory.resolve("workspace"), LabState.IMPORTED, 0, CREATED, CREATED));
            SQLiteDockerResourceJournal journal = new SQLiteDockerResourceJournal(dataSource);
            journal.reserve(DockerResourceRecord.reserved(
                    "0123456789abcdef0123456789abcdef", OWNERSHIP,
                    DockerResourceType.CONTAINER, "app", CREATED));

            assertThatThrownBy(() -> journal.reserve(DockerResourceRecord.reserved(
                            "fedcba9876543210fedcba9876543210", OWNERSHIP,
                            DockerResourceType.CONTAINER, "app", CREATED)))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> journal.reserve(DockerResourceRecord.reserved(
                            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            new LabOwnership("lab-a", "different-project"),
                            DockerResourceType.NETWORK,
                            "lab-network",
                            CREATED)))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    private LockedSQLiteDataSource open() {
        LockedSQLiteDataSource dataSource = new SQLiteDataSourceFactory()
                .create(temporaryDirectory.resolve("data-" + java.util.UUID.randomUUID()));
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate();
        return dataSource;
    }
}
