package dev.termestra.tasks.application.port.out;
import java.nio.file.Path;

public interface TasksDocumentStore {
    void initialize(Path workspace);
    String read(Path workspace);
    void write(Path workspace, String content);
}
