package dev.termestra.tasks.application.port.in;

public record TasksDocumentEvent(boolean snapshot, String content, String revision) {
    public TasksDocumentEvent(boolean snapshot, String content) {
        this(snapshot, content, TasksDocument.revisionOf(content));
    }
}
