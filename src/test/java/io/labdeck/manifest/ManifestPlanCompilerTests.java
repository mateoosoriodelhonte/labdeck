package io.labdeck.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class ManifestPlanCompilerTests {

    private final RestrictedManifestParser parser = new RestrictedManifestParser();
    private final ManifestPlanCompiler compiler = new ManifestPlanCompiler();

    @Test
    void compilesACompleteDeterministicPlanWithoutRuntimeState() throws IOException {
        LabManifest manifest = parser.parse(fixture("manifests/valid/full-v1.yaml"));

        ManifestPlan first = compiler.compile(manifest);
        ManifestPlan second = compiler.compile(parser.parse(fixture("manifests/valid/full-v1.yaml")));

        assertThat(first).isEqualTo(second);
        assertThat(first.manifestSha256()).matches("sha256:[a-f0-9]{64}");
        assertThat(first.services()).extracting(ManifestPlan.ServicePlan::id)
                .containsExactly("app", "cache", "database");
        assertThat(first.images()).containsExactly("postgres:17", "python:3.12", "redis:7");
        assertThat(first.volumes()).containsExactly("database-data");
        assertThat(first.workspaceMount()).isEqualTo("/workspace");
        assertThat(first.toString()).doesNotContain("/Users/", "127.0.0.1", "createdAt");
    }

    @Test
    void mapOrderDoesNotChangeThePlanOrFingerprint() {
        LabManifest first = parser.parse(manifestWithServices("""
                database:
                  image: postgres:17
                  environment:
                    POSTGRES_USER: student
                    POSTGRES_DB: assignment
                app:
                  image: python:3.12
                """));
        LabManifest second = parser.parse(manifestWithServices("""
                app:
                  image: python:3.12
                database:
                  environment:
                    POSTGRES_DB: assignment
                    POSTGRES_USER: student
                  image: postgres:17
                """));

        assertThat(compiler.compile(first)).isEqualTo(compiler.compile(second));
    }

    @Test
    void aSemanticChangeChangesTheFingerprint() {
        LabManifest first = parser.parse(manifestWithServices("""
                app:
                  image: python:3.12
                """));
        LabManifest second = parser.parse(manifestWithServices("""
                app:
                  image: python:3.13
                """));

        assertThat(compiler.compile(first).manifestSha256())
                .isNotEqualTo(compiler.compile(second).manifestSha256());
    }

    private static String manifestWithServices(String services) {
        return """
                version: 1
                name: Ordered lab
                workspace:
                  mount: /workspace
                services:
                """ + services.indent(2);
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream input = ManifestPlanCompilerTests.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Missing test fixture: " + name);
            }
            return input.readAllBytes();
        }
    }
}
