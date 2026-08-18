package dev.termestra.workspace.domain.model;

public record WorkspaceName(String value) {
    public WorkspaceName {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("workspace name must not be blank");
        value = value.trim();
    }
}
