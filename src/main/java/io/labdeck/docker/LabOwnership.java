package io.labdeck.docker;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record LabOwnership(String labId, String projectId) {

    public static final String MANAGED_LABEL = "io.labdeck.managed";
    public static final String LAB_LABEL = "io.labdeck.lab";
    public static final String PROJECT_LABEL = "io.labdeck.project";
    public static final String TYPE_LABEL = "io.labdeck.resource-type";
    public static final String LOGICAL_NAME_LABEL = "io.labdeck.logical-name";
    public static final String TOKEN_LABEL = "io.labdeck.ownership-token";
    public static final String IMAGE_REFERENCE_LABEL = "io.labdeck.image-reference";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    public LabOwnership {
        if (labId == null || !ID.matcher(labId).matches()
                || projectId == null || !ID.matcher(projectId).matches()) {
            throw new IllegalArgumentException("The lab or project ID is not valid.");
        }
    }

    public Map<String, String> labels(
            DockerResourceType type, String logicalName, String ownershipToken) {
        Objects.requireNonNull(type, "type");
        DockerResourceRecord.requireLogicalName(logicalName);
        DockerResourceRecord.requireOwnershipToken(ownershipToken);
        return Map.of(
                MANAGED_LABEL, "true",
                LAB_LABEL, labId,
                PROJECT_LABEL, projectId,
                TYPE_LABEL, type.labelValue(),
                LOGICAL_NAME_LABEL, logicalName,
                TOKEN_LABEL, ownershipToken);
    }
}
