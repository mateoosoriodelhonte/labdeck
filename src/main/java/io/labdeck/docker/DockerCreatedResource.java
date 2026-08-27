package io.labdeck.docker;

import java.util.Optional;

public record DockerCreatedResource(String id, Optional<String> identity) {

    public DockerCreatedResource {
        requireText(id, "Docker resource ID");
        identity = identity == null ? Optional.empty() : identity;
        identity.ifPresent(value -> requireText(value, "Docker resource identity"));
    }

    public static DockerCreatedResource identified(String id, String identity) {
        return new DockerCreatedResource(id, Optional.of(identity));
    }

    public static DockerCreatedResource withImmutableId(String id) {
        return new DockerCreatedResource(id, Optional.empty());
    }

    @Override
    public String toString() {
        return "DockerCreatedResource[id=" + id + ", identity="
                + (identity.isPresent() ? "<redacted>" : "<none>") + "]";
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 255
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("The " + name + " is not valid.");
        }
    }
}
