package dev.termestra.team.adapter.out.runtime;

import dev.termestra.team.application.port.in.DispatchDeliveryUseCase;
import dev.termestra.team.application.port.out.DispatchDeliveryScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, restartable runtime for the SQLite-authoritative dispatch delivery queue. */
public final class DispatchDeliveryRuntime implements DispatchDeliveryScheduler, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DispatchDeliveryRuntime.class);
    private static final int MAX_CONCURRENCY = 8;

    private final DispatchDeliveryUseCase deliveries;
    private final Semaphore wakeSignals = new Semaphore(0);
    private final AtomicBoolean started = new AtomicBoolean();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;

    public DispatchDeliveryRuntime(DispatchDeliveryUseCase deliveries) { this.deliveries = deliveries; }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        deliveries.recoverInterrupted();
        running = true;
        for (int index = 0; index < MAX_CONCURRENCY; index++) {
            boolean periodicReconciler = index == 0;
            workers.submit(() -> workLoop(periodicReconciler));
        }
        wake();
    }

    @Override public synchronized void wake() {
        if (wakeSignals.availablePermits() == 0) wakeSignals.release(MAX_CONCURRENCY);
    }

    private void workLoop(boolean periodicReconciler) {
        while (running) {
            try {
                if (deliveries.processNext()) continue;
                if (periodicReconciler) wakeSignals.tryAcquire(1, 1, TimeUnit.SECONDS);
                else wakeSignals.acquire();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                LOGGER.error("Dispatch delivery cycle failed", failure);
            }
        }
    }

    @Override public void close() {
        running = false;
        wakeSignals.release(MAX_CONCURRENCY);
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("Dispatch delivery workers did not terminate within five seconds");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
