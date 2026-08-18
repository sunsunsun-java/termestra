package dev.termestra.team.application.exception;
public final class TeamConflict extends RuntimeException {
    public TeamConflict(String message) { super(message); }
    public TeamConflict(String message, Throwable cause) { super(message, cause); }
}
