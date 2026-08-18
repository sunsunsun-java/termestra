package dev.termestra.tasks.application.port.in;

public final class TasksDocumentTooLarge extends RuntimeException {
    public TasksDocumentTooLarge(long maximumBytes) {
        super("Tasks document exceeds the " + maximumBytes + " byte limit");
    }
}
