package dev.termestra.platform.process;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/**
 * Terminates an owned process tree within a fixed deadline.
 *
 * <p>The JDK exposes descendants as a stream with no intrinsic bound. This helper keeps a bounded
 * set of process handles, refreshes descendants while parents are still alive, and does not return
 * until the graceful and forced termination windows have both been exhausted.</p>
 */
public final class ProcessTreeTerminator {
    private static final long POLL_NANOS = Duration.ofMillis(10).toNanos();

    private ProcessTreeTerminator() { }

    /**
     * Retains descendant identities across bounded attempts. This matters when the root exits before
     * one stubborn child: a fresh scan can no longer rediscover that child and must not report a
     * false successful termination.
     */
    public static TrackedProcessTree track(Process process){
        return new TrackedProcessTree(process);
    }

    public static boolean terminate(
            Process process,
            Duration gracefulTimeout,
            Duration forcedTimeout,
            int maxDescendants) {
        return track(process).terminate(gracefulTimeout,forcedTimeout,maxDescendants);
    }

    public static final class TrackedProcessTree{
        private final Process process;
        private final ProcessHandle root;
        private final Map<Long,ProcessHandle> descendants=new LinkedHashMap<>();
        private boolean descendantsFullyTracked=true;

        private TrackedProcessTree(Process process){
            this.process=Objects.requireNonNull(process,"process");
            this.root=ProcessHandle.of(process.pid()).orElse(null);
        }

        public synchronized boolean terminate(Duration gracefulTimeout,Duration forcedTimeout,
                                                int maxDescendants){
            requirePositive(gracefulTimeout,"gracefulTimeout");
            requirePositive(forcedTimeout,"forcedTimeout");
            if(maxDescendants<1)throw new IllegalArgumentException("maxDescendants must be positive");

            boolean[] interrupted={Thread.interrupted()};
            try{
                if(terminatePhase(process,root,descendants,gracefulTimeout,maxDescendants,false,
                        interrupted,this))return true;
                return terminatePhase(process,root,descendants,forcedTimeout,maxDescendants,true,
                        interrupted,this);
            }finally{
                if(interrupted[0]||Thread.interrupted())Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean terminatePhase(
            Process process,
            ProcessHandle root,
            Map<Long, ProcessHandle> descendants,
            Duration timeout,
            int maxDescendants,
            boolean force,
            boolean[] interrupted,
            TrackedProcessTree trackedTree) {
        long deadline = saturatedDeadline(timeout);
        while (true) {
            if(root!=null&&root.isAlive()&&!captureDescendants(root,descendants,maxDescendants)){
                trackedTree.descendantsFullyTracked=false;
            }
            for (ProcessHandle known : List.copyOf(descendants.values())) {
                if(known.isAlive()&&!captureDescendants(known,descendants,maxDescendants)){
                    trackedTree.descendantsFullyTracked=false;
                }
            }
            destroyChildren(descendants, force);
            if (process.isAlive()) {
                if (force) process.destroyForcibly();
                else process.destroy();
            }
            descendants.entrySet().removeIf(entry -> !entry.getValue().isAlive());
            if(!process.isAlive()&&descendants.isEmpty()&&trackedTree.descendantsFullyTracked)return true;
            if (System.nanoTime() - deadline >= 0) return false;
            LockSupport.parkNanos(Math.min(POLL_NANOS, Math.max(1, deadline - System.nanoTime())));
            if (Thread.interrupted()) interrupted[0] = true;
        }
    }

    /**
     * Captures at most {@code maxDescendants} handles while inspecting at most one unknown handle
     * beyond the remaining capacity. A false result is sticky at the caller: once a descendant was
     * not retained, a dead parent can no longer prove that the untracked process also exited.
     */
    private static boolean captureDescendants(
            ProcessHandle parent,
            Map<Long, ProcessHandle> known,
            int maxDescendants) {
        int remaining = maxDescendants - known.size();
        if(!parent.isAlive())return true;
        int[] discovered={0};
        try (var stream = parent.descendants()) {
            stream.filter(child->!known.containsKey(child.pid()))
                    .limit((long)Math.max(0,remaining)+1)
                    .forEach(child->{
                        if(discovered[0]++<remaining)known.putIfAbsent(child.pid(),child);
                    });
            return discovered[0]<=remaining;
        } catch (SecurityException ignored) {
            // Fail closed. The root is still terminated below, but denied enumeration means the
            // complete tree cannot be proven absent and resource ownership must be retained.
            return false;
        }
    }

    private static void destroyChildren(Map<Long, ProcessHandle> descendants, boolean force) {
        List<ProcessHandle> ordered = new ArrayList<>(descendants.values());
        for (int index = ordered.size() - 1; index >= 0; index--) {
            ProcessHandle child = ordered.get(index);
            if (!child.isAlive()) continue;
            if (force) child.destroyForcibly();
            else child.destroy();
        }
    }

    private static long saturatedDeadline(Duration timeout) {
        long now = System.nanoTime();
        long nanos = timeout.toNanos();
        long deadline = now + nanos;
        return deadline < now ? Long.MAX_VALUE : deadline;
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
