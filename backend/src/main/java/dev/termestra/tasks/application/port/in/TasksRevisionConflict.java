package dev.termestra.tasks.application.port.in;

public final class TasksRevisionConflict extends RuntimeException {
    private final TasksDocument current;

    public TasksRevisionConflict(TasksDocument current) {
        super("Tasks document changed since it was loaded");
        this.current = current;
    }

    public TasksDocument current() { return current; }
}
