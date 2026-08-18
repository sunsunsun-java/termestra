package dev.termestra.workspace.domain.model;

public record WorkspacePath(String value) {
    public WorkspacePath {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("workspace path must not be blank");
    }
}
