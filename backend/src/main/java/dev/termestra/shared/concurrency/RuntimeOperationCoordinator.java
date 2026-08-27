package dev.termestra.shared.concurrency;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Serializes runtime mutations by their exact workspace and agent identities.
 *
 * <p>Normal agent operations share a workspace read lock; exclusive workspace
 * lifecycle operations take its write lock. Lock acquisition is bounded by one
 * deadline per coordinator call. The operation itself is not timed here.</p>
 */
public final class RuntimeOperationCoordinator {
    private static final Duration DEFAULT_ACQUISITION_TIMEOUT = Duration.ofSeconds(2);

    private final Duration acquisitionTimeout;
    private final LongSupplier nanoTime;
    private final KeyedLockRegistry<String, ReentrantReadWriteLock> workspaces =
            new KeyedLockRegistry<>(ignored -> new ReentrantReadWriteLock(true));
    private final KeyedLockRegistry<AgentKey, ReentrantLock> agents =
            new KeyedLockRegistry<>(ignored -> new ReentrantLock(true));
    private final KeyedLockRegistry<String, ReentrantLock> workspacePaths =
            new KeyedLockRegistry<>(ignored -> new ReentrantLock(true));

    public RuntimeOperationCoordinator() {
        this(DEFAULT_ACQUISITION_TIMEOUT, System::nanoTime);
    }

    public RuntimeOperationCoordinator(Duration acquisitionTimeout) {
        this(acquisitionTimeout, System::nanoTime);
    }

    RuntimeOperationCoordinator(Duration acquisitionTimeout, LongSupplier nanoTime) {
        this.acquisitionTimeout = requirePositive(acquisitionTimeout);
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public <T> T withAgent(String workspaceId, String agentId, Supplier<T> operation) {
        String workspace = requireIdentifier(workspaceId, "workspaceId");
        String agent = requireIdentifier(agentId, "agentId");
        Objects.requireNonNull(operation, "operation");
        AcquisitionDeadline deadline = deadline();
        return withWorkspaceLock(workspace, false, deadline, () ->
                withAgentLock(new AgentKey(workspace, agent), deadline, operation));
    }

    public void withAgent(String workspaceId, String agentId, Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        withAgent(workspaceId, agentId, () -> {
            operation.run();
            return null;
        });
    }

    public <T> T withWorkspace(String workspaceId, Supplier<T> operation) {
        String workspace = requireIdentifier(workspaceId, "workspaceId");
        Objects.requireNonNull(operation, "operation");
        return withWorkspaceLock(workspace, false, deadline(), operation);
    }

    public void withWorkspace(String workspaceId, Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        withWorkspace(workspaceId, () -> {
            operation.run();
            return null;
        });
    }

    /** Serializes workspace initialization, deletion, and other exclusive lifecycle work. */
    public <T> T exclusivelyWithWorkspace(String workspaceId, Supplier<T> operation) {
        String workspace = requireIdentifier(workspaceId, "workspaceId");
        Objects.requireNonNull(operation, "operation");
        return withWorkspaceLock(workspace, true, deadline(), operation);
    }

    public void exclusivelyWithWorkspace(String workspaceId, Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        exclusivelyWithWorkspace(workspaceId, () -> {
            operation.run();
            return null;
        });
    }

    public <T> T deletingWorkspace(String workspaceId, Supplier<T> operation) {
        return exclusivelyWithWorkspace(workspaceId, operation);
    }

    public void deletingWorkspace(String workspaceId, Runnable operation) {
        deletingWorkspace(workspaceId, () -> {
            operation.run();
            return null;
        });
    }

    /** Serializes registration claims by their exact canonical source path. */
    public <T> T exclusivelyRegisteringWorkspacePath(String canonicalPath, Supplier<T> operation) {
        String path = requireIdentifier(canonicalPath, "canonicalPath");
        Objects.requireNonNull(operation, "operation");
        try (var retained = workspacePaths.retain(path)) {
            return acquired(retained.lock(), deadline(),
                    RuntimeOperationBusyException.workspacePath(path, acquisitionTimeout), operation);
        }
    }

    public void exclusivelyRegisteringWorkspacePath(String canonicalPath, Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        exclusivelyRegisteringWorkspacePath(canonicalPath, () -> {
            operation.run();
            return null;
        });
    }

    private <T> T withWorkspaceLock(String workspaceId, boolean exclusive,
                                    AcquisitionDeadline deadline, Supplier<T> operation) {
        try (var retained = workspaces.retain(workspaceId)) {
            ReentrantReadWriteLock workspaceLock = retained.lock();
            if (exclusive && workspaceLock.getReadHoldCount() > 0
                    && !workspaceLock.isWriteLockedByCurrentThread()) {
                throw new RuntimeOperationNestingException(
                        "A shared workspace operation cannot be upgraded to an exclusive operation: "
                                + workspaceId);
            }
            Lock requested = exclusive ? workspaceLock.writeLock() : workspaceLock.readLock();
            return acquired(requested, deadline,
                    RuntimeOperationBusyException.workspace(workspaceId, acquisitionTimeout), operation);
        }
    }

    private <T> T withAgentLock(AgentKey key, AcquisitionDeadline deadline, Supplier<T> operation) {
        try (var retained = agents.retain(key)) {
            return acquired(retained.lock(), deadline,
                    RuntimeOperationBusyException.agent(key.workspaceId(), key.agentId(), acquisitionTimeout),
                    operation);
        }
    }

    private static <T> T acquired(Lock lock, AcquisitionDeadline deadline,
                                  RuntimeOperationBusyException busy, Supplier<T> operation) {
        boolean locked = false;
        try {
            long remaining = deadline.remainingNanos();
            if (remaining <= 0 || !lock.tryLock(remaining, TimeUnit.NANOSECONDS)) throw busy;
            locked = true;
            return operation.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeOperationInterruptedException(
                    "Interrupted while waiting for another runtime operation to finish", interrupted);
        } finally {
            if (locked) lock.unlock();
        }
    }

    int retainedWorkspaceKeyCount() {
        return workspaces.size();
    }

    int retainedAgentKeyCount() {
        return agents.size();
    }

    int retainedWorkspacePathKeyCount() {
        return workspacePaths.size();
    }

    private AcquisitionDeadline deadline() {
        return AcquisitionDeadline.start(acquisitionTimeout, nanoTime);
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "acquisitionTimeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("acquisitionTimeout must be positive");
        }
        try {
            timeout.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("acquisitionTimeout is too large", overflow);
        }
        return timeout;
    }

    private static String requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private record AgentKey(String workspaceId, String agentId) { }

    private record AcquisitionDeadline(long startedAt, long budgetNanos, LongSupplier nanoTime) {
        static AcquisitionDeadline start(Duration timeout, LongSupplier nanoTime) {
            return new AcquisitionDeadline(nanoTime.getAsLong(), timeout.toNanos(), nanoTime);
        }

        long remainingNanos() {
            // nanoTime is an elapsed-time source whose absolute value may be negative and
            // may wrap. Subtraction remains correct for every supported positive budget;
            // a negative elapsed value means more than Long.MAX_VALUE nanos have passed.
            long elapsed = nanoTime.getAsLong() - startedAt;
            if (elapsed < 0 || elapsed >= budgetNanos) return 0;
            return budgetNanos - elapsed;
        }
    }

    private static final class KeyedLockRegistry<K, L> {
        private final ConcurrentHashMap<K, Entry<L>> entries = new ConcurrentHashMap<>();
        private final java.util.function.Function<K, L> factory;

        private KeyedLockRegistry(java.util.function.Function<K, L> factory) {
            this.factory = factory;
        }

        RetainedLock<K, L> retain(K key) {
            Entry<L> entry = entries.compute(key, (ignored, current) -> {
                Entry<L> value = current == null ? new Entry<>(factory.apply(key)) : current;
                value.references++;
                return value;
            });
            return new RetainedLock<>(this, key, entry);
        }

        void release(K key, Entry<L> expected) {
            entries.compute(key, (ignored, current) -> {
                if (current != expected) {
                    throw new IllegalStateException("Runtime lock registry identity changed unexpectedly");
                }
                current.references--;
                if (current.references < 0) {
                    throw new IllegalStateException("Runtime lock registry reference count became negative");
                }
                return current.references == 0 ? null : current;
            });
        }

        int size() {
            return entries.size();
        }

        private static final class Entry<L> {
            private final L lock;
            private int references;

            private Entry(L lock) {
                this.lock = lock;
            }
        }
    }

    private static final class RetainedLock<K, L> implements AutoCloseable {
        private final KeyedLockRegistry<K, L> registry;
        private final K key;
        private final KeyedLockRegistry.Entry<L> entry;
        private boolean closed;

        private RetainedLock(KeyedLockRegistry<K, L> registry, K key,
                             KeyedLockRegistry.Entry<L> entry) {
            this.registry = registry;
            this.key = key;
            this.entry = entry;
        }

        L lock() {
            return entry.lock;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            registry.release(key, entry);
        }
    }
}
