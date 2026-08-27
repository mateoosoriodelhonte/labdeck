package io.labdeck.manifest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class WorkspaceManifestLoader {

    public static final String MANIFEST_FILENAME = "labdeck.yml";

    private final ProjectPathPolicy paths;
    private final RestrictedManifestParser parser;
    private final ManifestPlanCompiler compiler;

    public WorkspaceManifestLoader() {
        this(new ProjectPathPolicy(), new RestrictedManifestParser(), new ManifestPlanCompiler());
    }

    WorkspaceManifestLoader(
            ProjectPathPolicy paths,
            RestrictedManifestParser parser,
            ManifestPlanCompiler compiler) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    public LoadedManifest load(Path selectedWorkspace) {
        ApprovedWorkspacePath workspace = paths.resolveWorkspace(selectedWorkspace);
        Path manifest = workspace.path().resolve(MANIFEST_FILENAME);
        try {
            if (Files.isSymbolicLink(manifest)) {
                throw failure(
                        WorkspaceManifestException.Reason.UNSAFE_FILE,
                        "The lab manifest cannot be a symbolic link.");
            }
            BasicFileAttributes before = attributes(manifest);
            workspace.verifyUnchanged();
            byte[] input;
            try (InputStream stream = Files.newInputStream(
                    manifest, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                input = stream.readNBytes(RestrictedManifestParser.MAX_MANIFEST_BYTES + 1);
            }
            BasicFileAttributes after = attributes(manifest);
            workspace.verifyUnchanged();
            if (!sameFile(before, after)) {
                throw failure(
                        WorkspaceManifestException.Reason.CHANGED_DURING_READ,
                        "The lab manifest changed while LabDeck read it. Retry the request.");
            }
            return new LoadedManifest(workspace, compiler.compile(parser.parse(input)));
        } catch (WorkspaceManifestException | ManifestValidationException exception) {
            throw exception;
        } catch (java.nio.file.NoSuchFileException exception) {
            throw failure(
                    WorkspaceManifestException.Reason.NOT_FOUND,
                    "The selected workspace does not contain labdeck.yml.");
        } catch (IOException | SecurityException exception) {
            throw failure(
                    WorkspaceManifestException.Reason.READ_FAILED,
                    "LabDeck could not read the workspace manifest safely.");
        }
    }

    private static BasicFileAttributes attributes(Path manifest) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                manifest, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.fileKey() == null) {
            throw failure(
                    WorkspaceManifestException.Reason.UNSAFE_FILE,
                    "The lab manifest must be a regular project file.");
        }
        return attributes;
    }

    private static boolean sameFile(BasicFileAttributes before, BasicFileAttributes after) {
        return Objects.equals(before.fileKey(), after.fileKey())
                && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    private static WorkspaceManifestException failure(
            WorkspaceManifestException.Reason reason, String message) {
        return new WorkspaceManifestException(reason, message);
    }

    public record LoadedManifest(ApprovedWorkspacePath workspace, ManifestPlan plan) {
        public LoadedManifest {
            Objects.requireNonNull(workspace, "workspace");
            Objects.requireNonNull(plan, "plan");
        }
    }
}
