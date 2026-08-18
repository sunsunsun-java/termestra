package dev.termestra.tasks.application.port.in;
@FunctionalInterface public interface TasksSubscription extends AutoCloseable { @Override void close(); }
