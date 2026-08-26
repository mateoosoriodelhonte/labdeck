package io.labdeck.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.labdeck.manifest.LabManifest.BuildSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectPathPolicyTests {

    private final ProjectPathPolicy policy = new ProjectPathPolicy();

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesARegularDockerfileInsideTheSelectedProject() throws IOException {
        Path context = Files.createDirectories(temporaryDirectory.resolve("containers/app"));
        Path dockerfile = Files.writeString(context.resolve("Containerfile"), "FROM scratch\n");

        ProjectPathPolicy.ResolvedBuildPaths resolved = policy.resolveBuild(
                temporaryDirectory, "app", new BuildSource("containers/app", "Containerfile"));

        assertThat(resolved.workspace()).isEqualTo(temporaryDirectory.toRealPath());
        assertThat(resolved.context()).isEqualTo(context.toRealPath());
        assertThat(resolved.dockerfile()).isEqualTo(dockerfile.toRealPath());
    }

    @Test
    void rejectsTraversalEvenIfAnUnvalidatedBuildRecordIsPassed() throws IOException {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside-build"));
        Files.writeString(outside.resolve("Dockerfile"), "FROM scratch\n");

        assertThatThrownBy(() -> policy.resolveBuild(
                        project, "app", new BuildSource("../outside-build", "Dockerfile")))
                .isInstanceOfSatisfying(ManifestValidationException.class, exception ->
                        assertThat(exception.problems().getFirst().code())
                                .isEqualTo(ManifestProblemCode.MANIFEST_TRAVERSAL_FORBIDDEN));
    }

    @Test
    void rejectsSymlinksInBuildPaths() throws IOException {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path outside = Files.createDirectories(temporaryDirectory.resolve("linked-build"));
        Files.writeString(outside.resolve("Dockerfile"), "FROM scratch\n");
        Files.createSymbolicLink(project.resolve("linked"), outside);

        assertThatThrownBy(() -> policy.resolveBuild(
                        project, "app", new BuildSource("linked", "Dockerfile")))
                .isInstanceOfSatisfying(ManifestValidationException.class, exception ->
                        assertThat(exception.problems().getFirst().code())
                                .isEqualTo(ManifestProblemCode.MANIFEST_TRAVERSAL_FORBIDDEN));
    }

    @Test
    void approvesARealWorkspaceAndDetectsPathReplacement() throws IOException {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        ApprovedWorkspacePath approved = policy.resolveWorkspace(project);

        approved.verifyUnchanged();
        Path moved = temporaryDirectory.resolve("moved-project");
        Files.move(project, moved);
        Files.createDirectories(project);

        assertThatThrownBy(approved::verifyUnchanged)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workspace changed");
    }

    @Test
    void rejectsASymlinkSelectedAsTheWorkspace() throws IOException {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path link = temporaryDirectory.resolve("project-link");
        Files.createSymbolicLink(link, project);

        assertThatThrownBy(() -> policy.resolveWorkspace(link))
                .isInstanceOfSatisfying(ManifestValidationException.class, exception ->
                        assertThat(exception.problems().getFirst().code())
                                .isEqualTo(ManifestProblemCode.MANIFEST_HOST_PATH_FORBIDDEN));
    }

    @Test
    void rejectsAFilesystemRootAsTheWorkspace() {
        Path root = temporaryDirectory.toAbsolutePath().getRoot();

        assertThatThrownBy(() -> policy.resolveBuild(root, "app", new BuildSource(".", "Dockerfile")))
                .isInstanceOfSatisfying(ManifestValidationException.class, exception ->
                        assertThat(exception.problems().getFirst().code())
                                .isEqualTo(ManifestProblemCode.MANIFEST_HOST_PATH_FORBIDDEN));
    }

    @Test
    void rejectsTheUserHomeAsTheWorkspace() {
        Path userHome = Path.of(System.getProperty("user.home"));

        assertThatThrownBy(() -> policy.resolveBuild(userHome, "app", new BuildSource(".", "Dockerfile")))
                .isInstanceOfSatisfying(ManifestValidationException.class, exception ->
                        assertThat(exception.problems().getFirst().code())
                                .isEqualTo(ManifestProblemCode.MANIFEST_HOST_PATH_FORBIDDEN));
    }
}
