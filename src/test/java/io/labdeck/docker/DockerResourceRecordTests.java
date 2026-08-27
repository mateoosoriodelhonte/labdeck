package io.labdeck.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DockerResourceRecordTests {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-08-26T20:00:00Z");

    @Test
    void exactOwnershipRequiresEveryRequiredAndResourceSpecificLabel() {
        DockerResourceRecord resource = DockerResourceRecord.reserved(
                TOKEN, new LabOwnership("lab-a", "project-a"),
                DockerResourceType.CONTAINER, "app", NOW);

        assertThat(resource.hasExactLabels(resource.labels())).isTrue();
        for (String key : resource.labels().keySet()) {
            Map<String, String> missing = new HashMap<>(resource.labels());
            missing.remove(key);
            assertThat(resource.hasExactLabels(missing)).as("missing %s", key).isFalse();
        }
        Map<String, String> wrongProject = new HashMap<>(resource.labels());
        wrongProject.put(LabOwnership.PROJECT_LABEL, "project-b");
        assertThat(resource.hasExactLabels(wrongProject)).isFalse();
    }

    @Test
    void stateAndStringFormDoNotLeakOrAcceptInvalidIdentity() {
        DockerResourceRecord reserved = DockerResourceRecord.reserved(
                TOKEN, new LabOwnership("lab-a", "project-a"),
                DockerResourceType.NETWORK, "lab-network", NOW);

        assertThat(reserved.toString()).contains("ownershipToken=<redacted>").doesNotContain(TOKEN);
        DockerResourceRecord dispatched = reserved.dispatch(NOW);
        assertThat(dispatched.activate(
                        DockerCreatedResource.withImmutableId("engine-id"), NOW).state())
                .isEqualTo(DockerResourceState.ACTIVE);
        assertThatThrownBy(() -> DockerResourceRecord.reserved(
                        "short", reserved.ownership(), DockerResourceType.NETWORK, "lab-network", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
