package io.labdeck.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LabRecordTests {

    @Test
    void appliesAnAllowedLifecycleTransitionWithARevision() {
        LabRecord imported = lab(LabState.IMPORTED);

        LabRecord starting = imported.transitionTo(LabState.STARTING, Instant.ofEpochMilli(2_000));

        assertThat(starting.state()).isEqualTo(LabState.STARTING);
        assertThat(starting.revision()).isEqualTo(1);
        assertThat(starting.updatedAt()).isEqualTo(Instant.ofEpochMilli(2_000));
    }

    @Test
    void rejectsAStateJumpAndAStaleTimestamp() {
        LabRecord imported = lab(LabState.IMPORTED);

        assertThatThrownBy(() -> imported.transitionTo(LabState.RUNNING, Instant.ofEpochMilli(2_000)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> imported.transitionTo(LabState.STARTING, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redactsTheWorkspaceFromDiagnosticText() {
        assertThat(lab(LabState.IMPORTED).toString())
                .contains("workspace=<local path>")
                .doesNotContain("student-secret-workspace");
    }

    private static LabRecord lab(LabState state) {
        return new LabRecord(
                "lab-1",
                "project-1",
                "Database lab",
                1,
                Path.of("/tmp/student-secret-workspace"),
                state,
                0,
                Instant.ofEpochMilli(1_000),
                Instant.ofEpochMilli(1_000));
    }
}
