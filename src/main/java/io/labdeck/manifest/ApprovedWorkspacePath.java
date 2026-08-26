package io.labdeck.manifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

public final class ApprovedWorkspacePath {

    private final Path path;
    private final String fileKey;

    private ApprovedWorkspacePath(Path path, String fileKey) {
        this.path = path;
        this.fileKey = fileKey;
    }

    static ApprovedWorkspacePath capture(Path selectedWorkspace) throws IOException {
        Path candidate = selectedWorkspace.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(candidate)) {
            throw new IOException("The selected workspace cannot be a symbolic link.");
        }
        Path real = candidate.toRealPath();
        BasicFileAttributes attributes = Files.readAttributes(
                real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.fileKey() == null) {
            throw new IOException("The selected workspace has no stable directory identity.");
        }
        return new ApprovedWorkspacePath(real, attributes.fileKey().toString());
    }

    public Path path() {
        return path;
    }

    public void verifyUnchanged() {
        try {
            if (Files.isSymbolicLink(path) || !path.toRealPath().equals(path)) {
                throw changed();
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()
                    || attributes.fileKey() == null
                    || !Objects.equals(fileKey, attributes.fileKey().toString())) {
                throw changed();
            }
        } catch (IOException | SecurityException exception) {
            throw new IllegalStateException("The approved workspace changed before the Docker mount.", exception);
        }
    }

    @Override
    public String toString() {
        return "ApprovedWorkspacePath[path=<local path>, fileKey=<redacted>]";
    }

    private static IllegalStateException changed() {
        return new IllegalStateException("The approved workspace changed before the Docker mount.");
    }
}
