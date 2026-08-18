package dev.termestra.tasks.application.port.in;

public final class TasksSubscriptionLimit extends RuntimeException {
    public TasksSubscriptionLimit(int maximum) {
        super("Tasks subscriber limit reached for workspace: " + maximum);
    }
}
