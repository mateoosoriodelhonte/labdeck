package io.labdeck.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.labdeck.manifest.LabManifest.ImageSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RestrictedManifestParserTests {

    private final RestrictedManifestParser parser = new RestrictedManifestParser();

    @Test
    void parsesTheCompleteV1FixtureIntoAnImmutableSortedModel() throws IOException {
        LabManifest manifest = parser.parse(fixture("manifests/valid/full-v1.yaml"));

        assertThat(manifest.version()).isEqualTo(1);
        assertThat(manifest.name()).isEqualTo("Database Assignment 4");
        assertThat(manifest.workspace().mount()).isEqualTo("/workspace");
        assertThat(manifest.services().keySet()).containsExactly("app", "cache", "database");
        assertThat(manifest.services().get("app").source())
                .isEqualTo(new ImageSource("python:3.12"));
        assertThat(manifest.services().get("app").ports())
                .extracting(LabManifest.Port::container)
                .containsExactly(8000);
        assertThat(manifest.services().get("database").volumes())
                .containsExactly(new LabManifest.VolumeMount(
                        "database-data", "/var/lib/postgresql/data", false));
        assertThat(manifest.resources().memoryBytes()).isEqualTo(1_000_000_000L);
        assertThat(manifest.resources().cpus()).isEqualByComparingTo("2");
        assertThat(manifest.tests()).isPresent();
        assertThat(manifest.tests().orElseThrow().command()).containsExactly("pytest", "-q");
        assertThatThrownBy(() -> manifest.services().put("other", manifest.services().get("app")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void canonicalizesMapAndServiceOrder() {
        LabManifest first = parser.parse(minimalManifest("""
                services:
                  database:
                    image: postgres:17
                    environment:
                      POSTGRES_USER: student
                      POSTGRES_DB: assignment
                  app:
                    image: python:3.12
                """));
        LabManifest second = parser.parse(minimalManifest("""
                services:
                  app:
                    image: python:3.12
                  database:
                    environment:
                      POSTGRES_DB: assignment
                      POSTGRES_USER: student
                    image: postgres:17
                """));

        assertThat(first).isEqualTo(second);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafeManifests")
    void rejectsNamedUnsafeInputs(
            String name, String yaml, ManifestProblemCode expectedCode, String expectedPath) {
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOfSatisfying(ManifestValidationException.class, exception -> {
                    assertThat(exception.problems())
                            .extracting(ManifestProblem::code)
                            .contains(expectedCode);
                    assertThat(exception.problems())
                            .filteredOn(problem -> problem.code() == expectedCode)
                            .extracting(ManifestProblem::path)
                            .contains(expectedPath);
                });
    }

    @Test
    void rejectsDuplicateKeysInsteadOfUsingTheLastValue() {
        String yaml = """
                version: 1
                name: First
                name: Second
                workspace:
                  mount: /workspace
                services:
                  app:
                    image: python:3.12
                """;

        assertSingleCode(yaml, ManifestProblemCode.MANIFEST_PARSE_ERROR);
    }

    @Test
    void rejectsMalformedYamlAndTrailingDocuments() {
        assertSingleCode("[", ManifestProblemCode.MANIFEST_PARSE_ERROR);
        assertSingleCode(minimalManifest("""
                services:
                  app:
                    image: python:3.12
                """) + "\n---\nname: hidden\n", ManifestProblemCode.MANIFEST_PARSE_ERROR);
    }

    @Test
    void rejectsInvalidUtf8AndOversizedInput() {
        assertThatThrownBy(() -> parser.parse(new byte[] {(byte) 0xc3, (byte) 0x28}))
                .isInstanceOfSatisfying(ManifestValidationException.class,
                        exception -> assertThat(exception.problems().getFirst().code())
                                .isEqualTo(ManifestProblemCode.MANIFEST_PARSE_ERROR));

        byte[] oversized = new byte[RestrictedManifestParser.MAX_MANIFEST_BYTES + 1];
        assertThatThrownBy(() -> parser.parse(oversized))
                .isInstanceOfSatisfying(ManifestValidationException.class,
                        exception -> assertThat(exception.problems().getFirst().code())
                                .isEqualTo(ManifestProblemCode.MANIFEST_PARSE_ERROR));
    }

    @Test
    void aliasesCannotHideForbiddenFields() {
        String yaml = """
                version: 1
                name: Alias attack
                workspace:
                  mount: /workspace
                services:
                  app:
                    image: python:3.12
                    <<: &escape
                      privileged: true
                """;

        assertThatThrownBy(() -> parser.parse(yaml)).isInstanceOf(ManifestValidationException.class);
    }

    private void assertSingleCode(String yaml, ManifestProblemCode expectedCode) {
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOfSatisfying(ManifestValidationException.class,
                        exception -> assertThat(exception.problems())
                                .extracting(ManifestProblem::code)
                                .contains(expectedCode));
    }

    private static Stream<Arguments> unsafeManifests() {
        return Stream.of(
                unsafeServiceField("privileged", "privileged: true",
                        ManifestProblemCode.MANIFEST_PRIVILEGED_FORBIDDEN, "/services/app/privileged"),
                unsafeServiceField("host pid", "pid: host",
                        ManifestProblemCode.MANIFEST_HOST_NAMESPACE_FORBIDDEN, "/services/app/pid"),
                unsafeServiceField("host network", "network_mode: HOST",
                        ManifestProblemCode.MANIFEST_HOST_NAMESPACE_FORBIDDEN, "/services/app/network_mode"),
                unsafeServiceField("device", "devices: [\"/dev/kvm:/dev/kvm\"]",
                        ManifestProblemCode.MANIFEST_DEVICE_FORBIDDEN, "/services/app/devices"),
                unsafeServiceField("capability", "cap_add: [\"SYS_ADMIN\"]",
                        ManifestProblemCode.MANIFEST_CAPABILITY_FORBIDDEN, "/services/app/cap_add"),
                unsafeServiceField("escape option", "security_opt: [\"seccomp=unconfined\"]",
                        ManifestProblemCode.MANIFEST_ESCAPE_OPTION_FORBIDDEN, "/services/app/security_opt"),
                unsafeServiceField("Docker socket", "volumes: [\"/var/run/docker.sock:/engine.sock\"]",
                        ManifestProblemCode.MANIFEST_DOCKER_SOCKET_FORBIDDEN, "/services/app/volumes/0"),
                unsafeServiceField("root mount", "volumes: [\"/:/host\"]",
                        ManifestProblemCode.MANIFEST_SENSITIVE_MOUNT_FORBIDDEN, "/services/app/volumes/0"),
                unsafeServiceField("host bind", "volumes: [\"./course:/workspace\"]",
                        ManifestProblemCode.MANIFEST_HOST_PATH_FORBIDDEN, "/services/app/volumes/0"),
                unsafeServiceField("shell string", "command: \"sleep infinity\"",
                        ManifestProblemCode.MANIFEST_VALUE_TYPE_INVALID, "/services/app/command"),
                unsafeServiceField("shell wrapper", "command: [\"sh\", \"-c\", \"echo unsafe\"]",
                        ManifestProblemCode.MANIFEST_SHELL_COMMAND_FORBIDDEN, "/services/app/command"),
                unsafeServiceField("bad host port", "ports: [{container: 8000, host: 0}]",
                        ManifestProblemCode.MANIFEST_PORT_POLICY_VIOLATION, "/services/app/ports/0/host"),
                unsafeServiceField("unknown Docker field", "extra_hosts: [\"host.docker.internal:host-gateway\"]",
                        ManifestProblemCode.MANIFEST_UNKNOWN_FIELD, "/services/app/extra_hosts"),
                Arguments.of("workspace host path", """
                                version: 1
                                name: Unsafe
                                workspace:
                                  mount: /workspace
                                  host: /Users/student/course
                                services:
                                  app:
                                    image: python:3.12
                                """,
                        ManifestProblemCode.MANIFEST_HOST_PATH_FORBIDDEN, "/workspace/host"),
                Arguments.of("build traversal", minimalManifest("""
                                services:
                                  app:
                                    build:
                                      context: ../outside
                                """),
                        ManifestProblemCode.MANIFEST_TRAVERSAL_FORBIDDEN, "/services/app/build/context"),
                Arguments.of("absolute build path", minimalManifest("""
                                services:
                                  app:
                                    build:
                                      context: /tmp/project
                                """),
                        ManifestProblemCode.MANIFEST_HOST_PATH_FORBIDDEN, "/services/app/build/context"),
                Arguments.of("sensitive volume target", minimalManifest("""
                                services:
                                  app:
                                    image: python:3.12
                                    volumes:
                                      - name: host-data
                                        target: /etc
                                """),
                        ManifestProblemCode.MANIFEST_SENSITIVE_MOUNT_FORBIDDEN,
                        "/services/app/volumes/0/target"),
                Arguments.of("invalid memory", minimalManifest("""
                                services:
                                  app:
                                    image: python:3.12
                                resources:
                                  memory: 999TB
                                """),
                        ManifestProblemCode.MANIFEST_RESOURCE_LIMIT_INVALID, "/resources/memory"),
                Arguments.of("invalid CPU", minimalManifest("""
                                services:
                                  app:
                                    image: python:3.12
                                resources:
                                  cpus: -1
                                """),
                        ManifestProblemCode.MANIFEST_RESOURCE_LIMIT_INVALID, "/resources/cpus"),
                Arguments.of("mutable image", minimalManifest("""
                                services:
                                  app:
                                    image: python:latest
                                """),
                        ManifestProblemCode.MANIFEST_MUTABLE_IMAGE_TAG_FORBIDDEN, "/services/app/image"));
    }

    private static Arguments unsafeServiceField(
            String name, String field, ManifestProblemCode code, String path) {
        return Arguments.of(name, minimalManifest("""
                services:
                  app:
                    image: python:3.12
                """ + indent(field, 4)), code, path);
    }

    private static String minimalManifest(String remainder) {
        return """
                version: 1
                name: Safe lab
                workspace:
                  mount: /workspace
                """ + remainder;
    }

    private static String indent(String value, int spaces) {
        String prefix = " ".repeat(spaces);
        return value.lines().map(line -> prefix + line).reduce("", (left, right) -> left + right + "\n");
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream input = RestrictedManifestParserTests.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Missing test fixture: " + name);
            }
            return input.readAllBytes();
        }
    }
}
