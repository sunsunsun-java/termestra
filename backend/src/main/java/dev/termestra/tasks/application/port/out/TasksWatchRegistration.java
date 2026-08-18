package dev.termestra.tasks.application.port.out;
@FunctionalInterface public interface TasksWatchRegistration extends AutoCloseable { @Override void close(); }
