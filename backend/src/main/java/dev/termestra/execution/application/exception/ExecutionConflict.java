package dev.termestra.execution.application.exception;

public final class ExecutionConflict extends RuntimeException {
    public ExecutionConflict(String message) { super(message); }
    public ExecutionConflict(String message, Throwable cause) { super(message, cause); }
}
