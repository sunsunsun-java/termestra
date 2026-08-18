package dev.termestra.tasks.application.port.out;
import java.nio.file.Path;
public interface TasksFileWatcher { TasksWatchRegistration watch(Path workspace, Runnable changed); }
