package dev.termestra.execution.adapter.out.pty;

import dev.termestra.execution.application.port.out.ProcessLaunchRequest;
import dev.termestra.execution.application.port.out.PseudoTerminalHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledOnOs(OS.MAC)
class Pty4jProcessLauncherTest {
    @Test void requestedEnvironmentOverridesInheritedValues(){
        Map<String,String> environment=Pty4jProcessLauncher.launchEnvironment(
                Map.of("PATH","/bin","TERMESTRA_PORT","3000"),
                Map.of("TERMESTRA_PORT","4000","TERMESTRA_AGENT_TOKEN","new-token"));

        assertEquals("/bin",environment.get("PATH"));
        assertEquals("4000",environment.get("TERMESTRA_PORT"));
        assertEquals("new-token",environment.get("TERMESTRA_AGENT_TOKEN"));
    }
    @Test void pausedOutputIsNotDeliveredUntilTheHandleIsResumed() throws Exception {
        PseudoTerminalHandle handle = new Pty4jProcessLauncher().start(new ProcessLaunchRequest(
                List.of("/bin/sh", "-c", "printf 'PAUSED-OUTPUT'; sleep 30"),
                Path.of("/tmp").toString(), Map.of(), 80, 24));
        CountDownLatch output = new CountDownLatch(1);
        try {
            handle.pauseOutput();
            handle.activate(ignored -> output.countDown(), ignored -> { });

            assertFalse(output.await(250, TimeUnit.MILLISECONDS),
                    "the PTY reader must honor a pause established before activation");

            handle.resumeOutput();
            assertTrue(output.await(5, TimeUnit.SECONDS),
                    "resuming must release the waiting PTY reader");
        } finally {
            handle.stopAndConfirm();
        }
    }

    @Test void blockingPtyOutputIsReadOnAPlatformThread() throws Exception {
        PseudoTerminalHandle handle = new Pty4jProcessLauncher().start(new ProcessLaunchRequest(
                List.of("/bin/sh", "-c", "printf 'READY'; sleep 30"),
                Path.of("/tmp").toString(), Map.of(), 80, 24));
        AtomicReference<Boolean> virtualThread = new AtomicReference<>();
        CountDownLatch output = new CountDownLatch(1);
        try {
            handle.activate(ignored -> {
                virtualThread.compareAndSet(null, Thread.currentThread().isVirtual());
                output.countDown();
            }, ignored -> { });

            assertTrue(output.await(5, TimeUnit.SECONDS), "fixture did not publish PTY output");
            assertFalse(virtualThread.get(),
                    "a native PTY read must not pin the virtual-thread carrier pool");
        } finally {
            handle.stopAndConfirm();
        }
    }

    @Test void stopBeforeActivationDoesNotWaitForANonexistentReaderAndForbidsLateActivation(){
        PseudoTerminalHandle handle=new Pty4jProcessLauncher().start(new ProcessLaunchRequest(
                List.of("/bin/sh","-c","sleep 30"),Path.of("/tmp").toString(),Map.of(),80,24));

        assertTrue(handle.stopAndConfirm());

        assertFalse(handle.alive());
        assertThrows(IllegalStateException.class,()->handle.activate(ignored->{},ignored->{}));
    }

    @Test void oneOutputListenerFailureDoesNotKillTheReaderOrTheRealPty() throws Exception {
        PseudoTerminalHandle handle = new Pty4jProcessLauncher().start(new ProcessLaunchRequest(
                List.of("/bin/sh", "-c", "for n in 1 2 3 4 5; do printf chunk-$n; sleep .05; done"),
                Path.of("/tmp").toString(), Map.of(), 80, 24));
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch outputAfterFailure = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);

        handle.activate(bytes -> {
            if (callbacks.incrementAndGet() == 1) throw new IllegalStateException("simulated listener failure");
            outputAfterFailure.countDown();
        }, ignored -> exited.countDown());

        assertTrue(outputAfterFailure.await(5, TimeUnit.SECONDS), "reader must continue after one callback failure");
        assertTrue(exited.await(5, TimeUnit.SECONDS), "real PTY must retain its natural lifecycle");
    }

    @Test void exitListenerFailureIsContainedInsideTheExitBoundary() throws Exception {
        PseudoTerminalHandle handle = new Pty4jProcessLauncher().start(new ProcessLaunchRequest(
                List.of("/bin/sh", "-c", "exit 0"), Path.of("/tmp").toString(), Map.of(), 80, 24));
        CountDownLatch outputClosed = new CountDownLatch(1);

        handle.activate(ignored -> { }, ignored -> {
            outputClosed.countDown();
            throw new IllegalStateException("simulated exit listener failure");
        });

        assertTrue(outputClosed.await(5, TimeUnit.SECONDS));
    }

    @Test void exitIsPublishedOnlyAfterTheFinalPtyOutputHasDrained() throws Exception {
        PseudoTerminalHandle handle = new Pty4jProcessLauncher().start(new ProcessLaunchRequest(
                List.of("/bin/sh", "-c", "head -c 262144 /dev/zero | tr '\\0' x; printf 'FINAL-TAIL'"),
                Path.of("/tmp").toString(), Map.of(), 80, 24));
        StringBuilder output = new StringBuilder();
        AtomicReference<String> outputAtExit = new AtomicReference<>();
        CountDownLatch exited = new CountDownLatch(1);

        handle.activate(bytes -> {
            synchronized (output) {
                output.append(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            }
        }, ignored -> {
            synchronized (output) {
                outputAtExit.set(output.toString());
            }
            exited.countDown();
        });

        assertTrue(exited.await(10, TimeUnit.SECONDS));
        assertTrue(outputAtExit.get().endsWith("FINAL-TAIL"));
        assertTrue(outputAtExit.get().length() >= 262_144 + "FINAL-TAIL".length());
    }

    @Test void stopReapsDescendantsOfTheRealPtyProcess() throws Exception {
        PseudoTerminalHandle handle = new Pty4jProcessLauncher().start(new ProcessLaunchRequest(
                List.of("/bin/sh", "-c", "sleep 30 & child=$!; printf '%s\\n' $child; wait $child"),
                Path.of("/tmp").toString(), Map.of(), 80, 24));
        StringBuilder output = new StringBuilder();
        CountDownLatch childPublished = new CountDownLatch(1);
        AtomicReference<Long> childPid = new AtomicReference<>();
        handle.activate(bytes -> {
            synchronized (output) {
                output.append(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                java.util.regex.Matcher pid = java.util.regex.Pattern.compile("(\\d+)").matcher(output);
                if (pid.find()) {
                    childPid.compareAndSet(null, Long.parseLong(pid.group(1)));
                    childPublished.countDown();
                }
            }
        }, ignored -> { });
        assertTrue(childPublished.await(5, TimeUnit.SECONDS), "fixture did not publish its child pid");

        assertTrue(handle.stopAndConfirm(),"the adapter must confirm the complete process tree");

        assertTrue(!handle.alive(), "stop must not return while the root PTY process is alive");
        assertTrue(ProcessHandle.of(childPid.get()).map(child -> !child.isAlive()).orElse(true),
                "stop must not return while a child process is alive");
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void naturalRootExitReapsAnOwnedBackgroundChildBeforePublishingExit() throws Exception{
        PseudoTerminalHandle handle=new Pty4jProcessLauncher().start(new ProcessLaunchRequest(
                List.of("/bin/sh","-c","sleep 30 & child=$!; printf '%s\\n' $child; exit 0"),
                Path.of("/tmp").toString(),Map.of(),80,24));
        StringBuilder output=new StringBuilder();
        AtomicReference<Long> childPid=new AtomicReference<>();
        CountDownLatch childPublished=new CountDownLatch(1);
        CountDownLatch exited=new CountDownLatch(1);
        try{
            handle.activate(bytes->{
                synchronized(output){
                    output.append(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
                    var pid=java.util.regex.Pattern.compile("(\\d+)").matcher(output);
                    if(pid.find()){
                        childPid.compareAndSet(null,Long.parseLong(pid.group(1)));
                        childPublished.countDown();
                    }
                }
            },ignored->exited.countDown());

            assertTrue(childPublished.await(5,TimeUnit.SECONDS),
                    "fixture did not publish its child pid");
            assertTrue(exited.await(8,TimeUnit.SECONDS),
                    "the owned background child must not pin natural exit indefinitely");
            assertTrue(ProcessHandle.of(childPid.get()).map(child->!child.isAlive()).orElse(true),
                    "natural root exit must reap its owned background child before releasing the run");
        }finally{
            Long pid=childPid.get();
            if(pid!=null)ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                    .ifPresent(ProcessHandle::destroyForcibly);
            if(handle.alive())handle.stopAndConfirm();
        }
    }
}
