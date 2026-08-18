package dev.termestra.tasks.adapter.out.filesystem;

import dev.termestra.tasks.application.port.in.TasksDocumentTooLarge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    private static void awaitCalls(AtomicInteger calls, int minimum) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (calls.get() < minimum && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(calls.get() >= minimum, "watcher did not observe the first edit");
    }
}
