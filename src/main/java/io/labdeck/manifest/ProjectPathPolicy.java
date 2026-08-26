package io.labdeck.manifest;

import io.labdeck.manifest.LabManifest.BuildSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ProjectPathPolicy {

    private static final Set<String> SENSITIVE_WORKSPACE_ROOTS = Set.of(
            "/boot", "/dev", "/etc", "/home", "/library", "/private", "/proc", "/root",
            "/run", "/system", "/sys", "/users", "/var", "c:/windows");

    public ResolvedBuildPaths resolveBuild(Path selectedWorkspace, String serviceId, BuildSource build) {
        if (selectedWorkspace == null || serviceId == null || build == null) {
            throw new IllegalArgumentException("Workspace, service ID, and build are required.");
        }

        String basePath = "/services/" + pointerSegment(serviceId) + "/build";
        try {
            Path workspace = selectedWorkspace.toRealPath();
            if (!Files.isDirectory(workspace) || isSensitiveWorkspaceRoot(workspace)) {
                throw violation(ManifestProblemCode.MANIFEST_HOST_PATH_FORBIDDEN, "/workspace",
                        "Select a project directory, not a home or system directory.");
            }

            Path contextCandidate = workspace.resolve(build.context()).normalize();
            requireContained(workspace, contextCandidate, basePath + "/context");
            rejectSymlinks(workspace, contextCandidate, basePath + "/context");
            Path context = contextCandidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isDirectory(context, LinkOption.NOFOLLOW_LINKS)) {
                throw violation(ManifestProblemCode.MANIFEST_HOST_PATH_FORBIDDEN, basePath + "/context",
                        "The build context must be a project directory.");
            }

            Path dockerfileCandidate = context.resolve(build.dockerfile()).normalize();
            requireContained(context, dockerfileCandidate, basePath + "/dockerfile");
            rejectSymlinks(context, dockerfileCandidate, basePath + "/dockerfile");
            Path dockerfile = dockerfileCandidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isRegularFile(dockerfile, LinkOption.NOFOLLOW_LINKS)) {
                throw violation(ManifestProblemCode.MANIFEST_HOST_PATH_FORBIDDEN, basePath + "/dockerfile",
                        "The Dockerfile must be a regular file inside the build context.");
            }
            return new ResolvedBuildPaths(workspace, context, dockerfile);
        } catch (ManifestValidationException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw violation(ManifestProblemCode.MANIFEST_HOST_PATH_FORBIDDEN, basePath,
                    "The project-local build paths could not be resolved safely.");
        }
    }

    private static void requireContained(Path parent, Path child, String path) {
        if (!child.startsWith(parent)) {
            throw violation(ManifestProblemCode.MANIFEST_TRAVERSAL_FORBIDDEN, path,
                    "The build path must stay inside the selected project.");
        }
    }

    private static void rejectSymlinks(Path parent, Path child, String path) {
        Path relative = parent.relativize(child);
        Path current = parent;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw violation(ManifestProblemCode.MANIFEST_TRAVERSAL_FORBIDDEN, path,
                        "Symbolic links are not supported in v1 build paths.");
            }
        }
    }

    private static boolean isSensitiveWorkspaceRoot(Path workspace) {
        if (workspace.getParent() == null) {
            return true;
        }
        String normalized = workspace.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (SENSITIVE_WORKSPACE_ROOTS.contains(normalized)) {
            return true;
        }
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return false;
        }
        try {
            return workspace.equals(Path.of(userHome).toRealPath());
        } catch (IOException | SecurityException exception) {
            return workspace.equals(Path.of(userHome).toAbsolutePath().normalize());
        }
    }

    private static ManifestValidationException violation(
            ManifestProblemCode code, String path, String message) {
        return new ManifestValidationException(List.of(new ManifestProblem(code, path, message)));
    }

    private static String pointerSegment(String value) {
        if (!value.matches("[a-z][a-z0-9-]{0,31}")) {
            return "<invalid-service>";
        }
        return value;
    }

    public record ResolvedBuildPaths(Path workspace, Path context, Path dockerfile) {
        public ResolvedBuildPaths {
            workspace = workspace.toAbsolutePath().normalize();
            context = context.toAbsolutePath().normalize();
            dockerfile = dockerfile.toAbsolutePath().normalize();
        }
    }

}
