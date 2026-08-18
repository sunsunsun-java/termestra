package dev.termestra.tasks.application.port.in;

public final class TasksDocumentAccessFailure extends RuntimeException {
    public TasksDocumentAccessFailure(String message, Throwable cause) { super(message, cause); }
}
