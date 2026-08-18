package dev.termestra.tasks.application.service;

import dev.termestra.tasks.application.port.in.TasksDocumentEvent;
import dev.termestra.tasks.application.port.in.TasksSubscription;
import dev.termestra.tasks.application.port.in.TasksUseCase;
import dev.termestra.tasks.application.port.in.TasksWorkspaceNotFound;
import dev.termestra.tasks.application.port.in.TasksDocument;
import dev.termestra.tasks.application.port.in.TasksRevisionConflict;
import dev.termestra.tasks.application.port.out.TasksDocumentStore;
import dev.termestra.tasks.application.port.out.TasksFileWatcher;
import dev.termestra.tasks.application.port.out.TasksWatchRegistration;
import dev.termestra.tasks.application.port.out.WorkspaceLocation;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

public final class TasksApplicationService implements TasksUseCase {
    private static final System.Logger LOGGER = System.getLogger(TasksApplicationService.class.getName());
    private static final int WORKSPACE_LOCK_STRIPES = 64;
    private static final int MAX_SUBSCRIBERS_PER_WORKSPACE = 16;
    private final WorkspaceLocation workspaces;
    private final TasksDocumentStore documents;
    private final TasksFileWatcher watcher;
    private final ConcurrentMap<String, CopyOnWriteArraySet<Subscriber>> listeners =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TasksWatchRegistration> watches = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> lastPublished = new ConcurrentHashMap<>();
    private final Object[] workspaceLocks = createWorkspaceLocks();

    public TasksApplicationService(
            WorkspaceLocation workspaces,
            TasksDocumentStore documents,
            TasksFileWatcher watcher) {
        this.workspaces = workspaces;
        this.documents = documents;
        this.watcher = watcher;
    }

    @Override
    public TasksDocument readDocument(String workspaceId) {
        synchronized (workspaceLock(workspaceId)) {
            return TasksDocument.from(documents.read(path(workspaceId)));
        }
    }

    @Override
    public void validateWorkspace(String workspaceId) {
        synchronized (workspaceLock(workspaceId)) {
            path(workspaceId);
        }
    }

    @Override
    public TasksDocument writeDocument(String workspaceId, String content, String expectedRevision) {
        synchronized (workspaceLock(workspaceId)) {
            String value = Objects.requireNonNull(content, "content must not be null");
            TasksDocument next=TasksDocument.from(value);
            Path workspace = path(workspaceId);
            TasksDocument current = TasksDocument.from(documents.read(workspace));
            if (expectedRevision != null && !expectedRevision.equals(current.revision())) {
                throw new TasksRevisionConflict(current);
            }
            documents.write(workspace, value);
            publishIfChangedLocked(workspaceId, value);
            return next;
        }
    }

    @Override
    public TasksSubscription observe(
            String workspaceId,
            Consumer<TasksDocumentEvent> listener,
            Runnable closed) {
        synchronized (workspaceLock(workspaceId)) {
            Path workspace = path(workspaceId);
            String snapshot = documents.read(workspace);
            TasksDocument initial=TasksDocument.from(snapshot);
            CopyOnWriteArraySet<Subscriber> values =
                    listeners.computeIfAbsent(workspaceId, ignored -> new CopyOnWriteArraySet<>());
            if(values.size()>=MAX_SUBSCRIBERS_PER_WORKSPACE)throw new dev.termestra.tasks.application.port.in.TasksSubscriptionLimit(MAX_SUBSCRIBERS_PER_WORKSPACE);
            Subscriber subscriber=new Subscriber(listener,closed);
            try {
                lastPublished.put(workspaceId, snapshot);
                listener.accept(new TasksDocumentEvent(true, initial.content(), initial.revision()));
                values.add(subscriber);
                watches.computeIfAbsent(workspaceId,
                        ignored -> watcher.watch(workspace, () -> publishCurrentFile(workspaceId, workspace)));
                publishIfChangedLocked(workspaceId, documents.read(workspace));
            } catch (RuntimeException error) {
                unsubscribeLocked(workspaceId, values, subscriber);
                throw error;
            }
            return () -> unsubscribe(workspaceId, values, subscriber);
        }
    }

    private void publishCurrentFile(String workspaceId, Path workspace) {
        synchronized (workspaceLock(workspaceId)) {
            publishIfChangedLocked(workspaceId, documents.read(workspace));
        }
    }

    private void publishIfChangedLocked(String workspaceId, String content) {
        CopyOnWriteArraySet<Subscriber> values = listeners.get(workspaceId);
        if (values == null || values.isEmpty()) return;
        if (Objects.equals(lastPublished.get(workspaceId), content)) return;
        TasksDocument document=TasksDocument.from(content);
        lastPublished.put(workspaceId, content);
        TasksDocumentEvent event = new TasksDocumentEvent(false, document.content(), document.revision());
        for (Subscriber subscriber : values) {
            try {
                subscriber.listener().accept(event);
            } catch (RuntimeException deliveryFailure) {
                values.remove(subscriber);
                closeSubscriber(subscriber,workspaceId);
                LOGGER.log(System.Logger.Level.WARNING,
                        "Removed failed tasks document subscriber for workspace " + workspaceId,
                        deliveryFailure);
            }
        }
        closeWatchIfEmpty(workspaceId, values);
    }

    private void unsubscribe(
            String workspaceId,
            CopyOnWriteArraySet<Subscriber> values,
            Subscriber subscriber) {
        synchronized (workspaceLock(workspaceId)) {
            unsubscribeLocked(workspaceId, values, subscriber);
        }
    }

    private void unsubscribeLocked(String workspaceId,
            CopyOnWriteArraySet<Subscriber> values,
            Subscriber subscriber) {
        values.remove(subscriber);
        closeWatchIfEmpty(workspaceId, values);
    }

    private void closeWatchIfEmpty(String workspaceId, CopyOnWriteArraySet<Subscriber> values) {
        if (!values.isEmpty()) return;
        listeners.remove(workspaceId, values);
        lastPublished.remove(workspaceId);
        TasksWatchRegistration registration = watches.remove(workspaceId);
        closeRegistration(registration, workspaceId);
    }

    @Override public void forgetWorkspace(String workspaceId){
        synchronized(workspaceLock(workspaceId)){
            CopyOnWriteArraySet<Subscriber> values=listeners.remove(workspaceId);
            lastPublished.remove(workspaceId);
            TasksWatchRegistration registration=watches.remove(workspaceId);
            closeRegistration(registration, workspaceId);
            if(values!=null)for(Subscriber subscriber:values)closeSubscriber(subscriber,workspaceId);
        }
    }

    @Override public void close(){java.util.Set<String> workspaceIds=new java.util.HashSet<>();workspaceIds.addAll(listeners.keySet());workspaceIds.addAll(watches.keySet());for(String workspaceId:workspaceIds)forgetWorkspace(workspaceId);}

    private static void closeSubscriber(Subscriber subscriber,String workspaceId){try{subscriber.closed().run();}catch(RuntimeException failure){LOGGER.log(System.Logger.Level.WARNING,"Failed to close tasks subscriber for workspace "+workspaceId,failure);}}

    private static void closeRegistration(TasksWatchRegistration registration, String workspaceId) {
        if (registration == null) return;
        try { registration.close(); }
        catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Failed to close tasks watcher for workspace " + workspaceId, failure);
        }
    }

    private Path path(String workspaceId) {
        return workspaces.find(workspaceId)
                .orElseThrow(() -> new TasksWorkspaceNotFound(workspaceId));
    }

    private static Object[] createWorkspaceLocks() {
        Object[] locks = new Object[WORKSPACE_LOCK_STRIPES];
        java.util.Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    private Object workspaceLock(String workspaceId) {
        int hash = workspaceId.hashCode();
        hash ^= hash >>> 16;
        return workspaceLocks[hash & (WORKSPACE_LOCK_STRIPES - 1)];
    }

    private record Subscriber(Consumer<TasksDocumentEvent> listener,Runnable closed){Subscriber{Objects.requireNonNull(listener);Objects.requireNonNull(closed);}}
}
