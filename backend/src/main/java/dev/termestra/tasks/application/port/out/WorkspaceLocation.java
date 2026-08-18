package dev.termestra.tasks.application.port.out;
import java.nio.file.Path;
import java.util.Optional;
public interface WorkspaceLocation { Optional<Path> find(String workspaceId); }
