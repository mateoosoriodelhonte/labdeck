package io.labdeck.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceManifestLoaderTests {

    @TempDir
    Path temporaryDirectory;

    private final WorkspaceManifestLoader loader = new WorkspaceManifestLoader();

    @Test
    void loadsOnlyTheBoundedRegularProjectManifest() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Files.writeString(workspace.resolve(WorkspaceManifestLoader.MANIFEST_FILENAME), manifest());

        WorkspaceManifestLoader.LoadedManifest loaded = loader.load(workspace);

        assertThat(loaded.workspace().path()).isEqualTo(workspace.toRealPath());
        assertThat(loaded.plan().name()).isEqualTo("API fixture");
        assertThat(loaded.plan().manifestSha256()).startsWith("sha256:");
    }

    @Test
    void rejectsMissingDirectoryAndSymlinkManifestWithoutLeakingPaths() throws Exception {
        Path missingManifest = Files.createDirectories(temporaryDirectory.resolve("missing-manifest"));
        assertThatThrownBy(() -> loader.load(missingManifest))
                .isInstanceOfSatisfying(WorkspaceManifestException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(WorkspaceManifestException.Reason.NOT_FOUND);
                    assertThat(exception.getMessage()).doesNotContain(missingManifest.toString());
                });

        Path workspace = Files.createDirectories(temporaryDirectory.resolve("symlink-workspace"));
        Path outside = temporaryDirectory.resolve("outside.yml");
        Files.writeString(outside, manifest());
        Files.createSymbolicLink(workspace.resolve(WorkspaceManifestLoader.MANIFEST_FILENAME), outside);

        assertThatThrownBy(() -> loader.load(workspace))
                .isInstanceOfSatisfying(WorkspaceManifestException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(WorkspaceManifestException.Reason.UNSAFE_FILE);
                    assertThat(exception.getMessage()).doesNotContain(outside.toString());
                });
    }

    @Test
    void rejectsDirectoryAndOversizedManifest() throws Exception {
        Path directoryManifest = Files.createDirectories(temporaryDirectory.resolve("directory-manifest"));
        Files.createDirectory(directoryManifest.resolve(WorkspaceManifestLoader.MANIFEST_FILENAME));
        assertThatThrownBy(() -> loader.load(directoryManifest))
                .isInstanceOfSatisfying(WorkspaceManifestException.class, exception ->
                        assertThat(exception.reason()).isEqualTo(WorkspaceManifestException.Reason.UNSAFE_FILE));

        Path oversized = Files.createDirectories(temporaryDirectory.resolve("oversized"));
        Files.write(
                oversized.resolve(WorkspaceManifestLoader.MANIFEST_FILENAME),
                new byte[RestrictedManifestParser.MAX_MANIFEST_BYTES + 1]);
        assertThatThrownBy(() -> loader.load(oversized))
                .isInstanceOfSatisfying(ManifestValidationException.class, exception ->
                        assertThat(exception.problems().getFirst().code())
                                .isEqualTo(ManifestProblemCode.MANIFEST_PARSE_ERROR));
    }

    private static String manifest() {
        return """
                version: 1
                name: API fixture
                workspace:
                  mount: /workspace
                services:
                  app:
                    image: busybox:1.37
                    environment:
                      LAB_SECRET: never-return-this-value
                resources:
                  memory: 256MiB
                  cpus: 0.5
                """;
    }
}
