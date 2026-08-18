package dev.termestra.team.domain.model;

public record TaskText(String value) {
    public TaskText {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("task text must not be blank");
        }
    }
}

