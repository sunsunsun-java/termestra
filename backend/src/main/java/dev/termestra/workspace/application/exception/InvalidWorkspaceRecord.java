package dev.termestra.workspace.application.exception;

/** A persisted workspace cannot be projected safely into the application boundary. */
public final class InvalidWorkspaceRecord extends RuntimeException {
    public InvalidWorkspaceRecord() {
        super("Workspace record contains an invalid path");
    }
}
