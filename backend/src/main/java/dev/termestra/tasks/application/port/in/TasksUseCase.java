package dev.termestra.tasks.application.port.in;
import java.util.function.Consumer;
public interface TasksUseCase {
    TasksDocument readDocument(String workspaceId);
    TasksDocument writeDocument(String workspaceId, String content, String expectedRevision);
    default void validateWorkspace(String workspaceId) { readDocument(workspaceId); }
    default String read(String workspaceId) { return readDocument(workspaceId).content(); }
    default String write(String workspaceId, String content) { return writeDocument(workspaceId, content, null).content(); }
    default TasksSubscription observe(String workspaceId, Consumer<TasksDocumentEvent> listener) {
        return observe(workspaceId, listener, () -> { });
    }
    TasksSubscription observe(String workspaceId, Consumer<TasksDocumentEvent> listener, Runnable closed);
    void forgetWorkspace(String workspaceId);
    void close();
}
