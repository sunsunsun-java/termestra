package dev.termestra.workspace.adapter.out.filesystem;

import dev.termestra.workspace.application.exception.InvalidWorkspacePath;
import dev.termestra.workspace.application.port.out.WorkspacePathResolver;
import dev.termestra.workspace.application.port.in.WorkspaceInputLimits;
import dev.termestra.workspace.domain.model.WorkspacePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

public final class NioWorkspacePathResolver implements WorkspacePathResolver {
    @Override public WorkspacePath resolveDirectory(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) throw new InvalidWorkspacePath("Workspace path is required");
        WorkspaceInputLimits.validatePath(rawPath);
        Path requested = Path.of(rawPath).toAbsolutePath().normalize();
        try {
            Path real = requested.toRealPath();
            if (!Files.isDirectory(real)) throw new InvalidWorkspacePath("Workspace path is not a directory: " + rawPath);
            return new WorkspacePath(real.toString());
        } catch (NoSuchFileException error) {
            throw new InvalidWorkspacePath("Workspace path does not exist: " + rawPath);
        } catch (IOException error) {
            throw new InvalidWorkspacePath("Workspace path does not exist: " + rawPath);
        }
    }
}
