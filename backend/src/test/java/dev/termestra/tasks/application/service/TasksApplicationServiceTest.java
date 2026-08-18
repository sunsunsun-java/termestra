package dev.termestra.tasks.application.service;

import dev.termestra.tasks.application.port.in.TasksDocumentEvent;
import dev.termestra.tasks.application.port.in.TasksDocument;
import dev.termestra.tasks.application.port.in.TasksRevisionConflict;
import dev.termestra.tasks.application.port.in.TasksDocumentTooLarge;
import dev.termestra.tasks.application.port.in.TasksSubscriptionLimit;
import dev.termestra.tasks.application.port.out.TasksDocumentStore;
import dev.termestra.tasks.application.port.out.TasksFileWatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TasksApplicationServiceTest {
    @TempDir Path workspace;

    @Test
    void emitsOneAtomicSnapshotThenDeduplicatesApiAndWatcherUpdates() {
        AtomicReference<String> document = new AtomicReference<>("initial");
        AtomicReference<Runnable> changed = new AtomicReference<>();
        TasksApplicationService service = service(document,
                (path, callback) -> { changed.set(callback); return () -> { }; });
        List<TasksDocumentEvent> events = new ArrayList<>();

        service.observe("workspace", events::add);
        service.write("workspace", "api update");
        changed.get().run();
        document.set("external update");
        changed.get().run();
        changed.get().run();

        assertEquals(List.of(
                new TasksDocumentEvent(true, "initial"),
                new TasksDocumentEvent(false, "api update"),
                new TasksDocumentEvent(false, "external update")), events);
    }

    @Test
    void deliversAChangeDuringWatchRegistrationAfterTheSnapshot() {
        AtomicReference<String> document = new AtomicReference<>("before watch");
        TasksApplicationService service = service(document, (path, changed) -> {
            document.set("during watch");
            changed.run();
            return () -> { };
        });
        List<TasksDocumentEvent> events = new ArrayList<>();

        service.observe("workspace", events::add);

        assertEquals(List.of(
                new TasksDocumentEvent(true, "before watch"),
                new TasksDocumentEvent(false, "during watch")), events);
    }

    @Test
    void removesAFailedSubscriberWithoutBlockingOtherSubscribersOrLaterUpdates() {
        AtomicReference<String> document = new AtomicReference<>("initial");
        AtomicReference<Runnable> changed = new AtomicReference<>();
        TasksApplicationService service = service(document,
                (path, callback) -> { changed.set(callback); return () -> { }; });
        AtomicInteger failingCalls = new AtomicInteger();
        List<TasksDocumentEvent> received = new ArrayList<>();
        service.observe("workspace", event -> {
            if (!event.snapshot()) {
                failingCalls.incrementAndGet();
                throw new IllegalStateException("subscriber failed");
            }
        });
        service.observe("workspace", received::add);

        document.set("first");
        changed.get().run();
        document.set("second");
        changed.get().run();

        assertEquals(1, failingCalls.get());
        assertEquals(List.of(
                new TasksDocumentEvent(true, "initial"),
                new TasksDocumentEvent(false, "first"),
                new TasksDocumentEvent(false, "second")), received);
    }

    @Test
    void aSlowWriteDoesNotBlockAnUnrelatedWorkspace() throws Exception {
        Path firstPath = workspace.resolve("first");
        Path secondPath = workspace.resolve("second");
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<String> first = new AtomicReference<>("a");
        AtomicReference<String> second = new AtomicReference<>("b");
        TasksDocumentStore store = new TasksDocumentStore() {
            @Override public void initialize(Path path) { }
            @Override public String read(Path path) { return path.equals(firstPath) ? first.get() : second.get(); }
            @Override public void write(Path path, String content) {
                if (path.equals(firstPath)) {
                    firstEntered.countDown();
                    try { releaseFirst.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IllegalStateException(interrupted); }
                    first.set(content);
                } else second.set(content);
            }
        };
        TasksApplicationService service = new TasksApplicationService(
                id -> switch (id) {case "workspace-a" -> Optional.of(firstPath);case "workspace-b" -> Optional.of(secondPath);default -> Optional.empty();},
                store,(path,changed)->()->{});
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var slow = executor.submit(() -> service.write("workspace-a", "slow"));
            assertEquals(true, firstEntered.await(1, TimeUnit.SECONDS));
            var unrelated = executor.submit(() -> service.write("workspace-b", "fast"));
            assertEquals("fast", unrelated.get(1, TimeUnit.SECONDS));
            releaseFirst.countDown();
            assertEquals("slow", slow.get(1, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void concurrentWritesToTheSameWorkspaceAreSerializedAndCannotLoseAnUpdate() throws Exception {
        AtomicReference<String> document = new AtomicReference<>("initial");
        TasksApplicationService service = service(document, (path, changed) -> () -> { });
        String expectedRevision = TasksDocument.from("initial").revision();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> concurrentWrite(
                    service, expectedRevision, "first", ready, start));
            var second = executor.submit(() -> concurrentWrite(
                    service, expectedRevision, "second", ready, start));
            assertTrue(ready.await(1, TimeUnit.SECONDS));
            start.countDown();

            Object firstResult = first.get(1, TimeUnit.SECONDS);
            Object secondResult = second.get(1, TimeUnit.SECONDS);
            List<Object> results = List.of(firstResult, secondResult);
            assertEquals(1, results.stream().filter(TasksDocument.class::isInstance).count());
            assertEquals(1, results.stream().filter(TasksRevisionConflict.class::isInstance).count());

            TasksDocument written = results.stream().filter(TasksDocument.class::isInstance)
                    .map(TasksDocument.class::cast).findFirst().orElseThrow();
            TasksRevisionConflict conflict = results.stream().filter(TasksRevisionConflict.class::isInstance)
                    .map(TasksRevisionConflict.class::cast).findFirst().orElseThrow();
            assertEquals(written, service.readDocument("workspace"));
            assertEquals(written, conflict.current());
        } finally {
            start.countDown();
        }
    }

    @Test
    void rejectsJsonAmplifiedContentBeforeReplacingThePersistedDocument() {
        AtomicReference<String> document = new AtomicReference<>("unchanged");
        TasksApplicationService service = service(document, (path, changed) -> () -> { });
        String jsonAmplified = "\u0001".repeat(
                Math.toIntExact(TasksDocument.MAX_TRANSPORT_CONTENT_BYTES / 6 + 1));

        assertThrows(TasksDocumentTooLarge.class,
                () -> service.writeDocument("workspace", jsonAmplified, null));
        assertEquals("unchanged", document.get());
    }

    @Test
    void capsSubscribersAndReleasesTheWatcherAndSubscribersWhenAWorkspaceIsForgotten() {
        AtomicReference<String> document = new AtomicReference<>("initial");
        AtomicInteger watcherClosed = new AtomicInteger();
        AtomicInteger subscriberClosed = new AtomicInteger();
        TasksApplicationService service = service(document,
                (path, changed) -> watcherClosed::incrementAndGet);

        for (int index = 0; index < 16; index++) {
            service.observe("workspace", ignored -> { }, subscriberClosed::incrementAndGet);
        }
        assertThrows(TasksSubscriptionLimit.class,
                () -> service.observe("workspace", ignored -> { }, subscriberClosed::incrementAndGet));

        service.forgetWorkspace("workspace");

        assertEquals(1, watcherClosed.get());
        assertEquals(16, subscriberClosed.get());
    }

    private static Object concurrentWrite(TasksApplicationService service, String expectedRevision,
                                          String content, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            return service.writeDocument("workspace", content, expectedRevision);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (TasksRevisionConflict conflict) {
            return conflict;
        }
    }

    private TasksApplicationService service(
            AtomicReference<String> document,
            TasksFileWatcher watcher) {
        TasksDocumentStore store = new TasksDocumentStore() {
            @Override public void initialize(Path ignored) { }
            @Override public String read(Path ignored) { return document.get(); }
            @Override public void write(Path ignored, String content) { document.set(content); }
        };
        return new TasksApplicationService(
                id -> "workspace".equals(id) ? Optional.of(workspace) : Optional.empty(),
                store,
                watcher);
    }
}
