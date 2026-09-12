package dev.termestra.tasks.adapter.out.filesystem;

import dev.termestra.tasks.application.port.in.TasksDocumentTooLarge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.WatchService;
import java.nio.file.WatchKey;
import java.nio.file.WatchEvent;
import java.nio.file.Watchable;
import java.nio.file.StandardWatchEventKinds;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NioTasksFileWatcherTest {
    @TempDir Path workspace;

    @Test void keepsWatchingAfterARecoverableInvalidDocument() throws Exception {
        Path metadata = Files.createDirectories(workspace.resolve(".termestra"));
        Path tasks = Files.writeString(metadata.resolve("tasks.md"), "initial");
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        try (var registration = new NioTasksFileWatcher().watch(workspace, () -> {
            if (calls.incrementAndGet() == 1) throw new TasksDocumentTooLarge(1);
            recovered.countDown();
        })) {
            Files.writeString(tasks, "invalid");
            awaitCalls(calls, 1);
            Files.writeString(tasks, "valid again");
            assertTrue(recovered.await(3, TimeUnit.SECONDS), "watcher did not observe a valid edit after the rejected edit");
        }
    }

    @Test void reRegistersAfterTheTasksDirectoryIsDeletedAndRecreated() throws Exception {
        Path metadata = Files.createDirectories(workspace.resolve(".termestra"));
        Path tasks = Files.writeString(metadata.resolve("tasks.md"), "initial");
        CountDownLatch recreated = new CountDownLatch(1);
        try (var registration = new NioTasksFileWatcher().watch(workspace, () -> {
            try {
                if (Files.exists(tasks) && "recreated".equals(Files.readString(tasks))) {
                    recreated.countDown();
                }
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
        })) {
            Files.delete(tasks);
            Files.delete(metadata);
            Thread.sleep(250);
            Files.createDirectories(metadata);
            Files.writeString(tasks, "recreated");

            assertTrue(recreated.await(3, TimeUnit.SECONDS),
                    "watcher did not resume after the tasks directory was recreated");
        }
    }

    @Test void closesTheNativeWatchServiceWhenInitialRegistrationFails() throws Exception {
        AtomicReference<WatchService> opened = new AtomicReference<>();
        NioTasksFileWatcher watcher = new NioTasksFileWatcher(() -> {
            WatchService value = FileSystems.getDefault().newWatchService();
            opened.set(value);
            return value;
        });

        assertThrows(IllegalStateException.class, () -> watcher.watch(workspace, () -> { }));

        assertThrows(ClosedWatchServiceException.class, () -> opened.get().poll());
    }

    @Test void refreshesTheDocumentWhenOverflowHasDiscardedTheIndividualFileEvents() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicInteger refreshes = new AtomicInteger();
        WatchEvent<Object> overflow = new WatchEvent<>() {
            @Override public Kind<Object> kind() { return StandardWatchEventKinds.OVERFLOW; }
            @Override public int count() { return 1; }
            @Override public Object context() { return null; }
        };
        WatchKey key = new WatchKey() {
            @Override public boolean isValid() { return true; }
            @Override public List<WatchEvent<?>> pollEvents() { return List.of(overflow); }
            @Override public boolean reset() { open.set(false); return true; }
            @Override public void cancel() { }
            @Override public Watchable watchable() { return workspace.resolve(".termestra"); }
        };
        try (WatchService watch = new WatchService() {
            @Override public WatchKey take() { return key; }
            @Override public WatchKey poll() { return key; }
            @Override public WatchKey poll(long timeout, TimeUnit unit) { return key; }
            @Override public void close() { open.set(false); }
        }) {
            // Inject the OS overflow signal without depending on host queue sizes or timing.
            var loop = NioTasksFileWatcher.class.getDeclaredMethod("run",
                    WatchService.class, Path.class, AtomicBoolean.class, Runnable.class);
            loop.setAccessible(true);
            loop.invoke(null, watch, workspace.resolve(".termestra"), open,
                    (Runnable) refreshes::incrementAndGet);
        }

        assertEquals(1, refreshes.get());
    }

    private static void awaitCalls(AtomicInteger calls, int minimum) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (calls.get() < minimum && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(calls.get() >= minimum, "watcher did not observe the first edit");
    }
}
