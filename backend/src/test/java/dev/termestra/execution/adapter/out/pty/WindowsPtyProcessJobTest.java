package dev.termestra.execution.adapter.out.pty;

import com.sun.jna.Native;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsPtyProcessJobTest {
    private static final Duration SHORT=Duration.ofMillis(20);

    @Test void suspendedRootIsClaimedByAKillOnCloseJobBeforeItCanRun(){
        FakeNative facade=new FakeNative();
        WindowsPtyProcessJob job=WindowsPtyProcessJob.create(facade);

        job.claimSuspended(42);
        job.requireClaimed(42);

        assertEquals(1,facade.configureCalls);
        assertEquals(42,facade.assignedPid);
        assertFalse(facade.closed,"ownership must remain alive for the complete PTY lifecycle");
    }

    @Test void naturalRootExitStillTerminatesAndConfirmsAJobOwnedBackgroundChild(){
        FakeNative facade=new FakeNative();
        facade.activeProcesses.add(1L); // root has exited, but its background child remains
        facade.activeProcesses.add(1L);
        facade.activeProcesses.add(0L);
        WindowsPtyProcessJob job=WindowsPtyProcessJob.create(facade);
        job.claimSuspended(42);

        assertTrue(job.terminate(SHORT));

        assertEquals(1,facade.terminateCalls);
        assertTrue(facade.closed,"the job handle is released only after active_processes reaches zero");
    }

    @Test void anUnknownTerminationErrorIsFailClosedAndRetainsTheJobForRetry(){
        FakeNative facade=new FakeNative();
        facade.activeProcesses.add(1L);
        facade.terminateFailure=new WindowsPtyProcessJob.NativeFailure("TerminateJobObject",5);
        WindowsPtyProcessJob job=WindowsPtyProcessJob.create(facade);
        job.claimSuspended(42);

        assertFalse(job.terminate(SHORT));

        assertFalse(facade.closed,"unknown termination state must retain ownership for a later retry");
    }

    @Test void anUnknownAccountingErrorCannotBeReportedAsAnEmptyJob(){
        FakeNative facade=new FakeNative();
        facade.accountingFailure=new WindowsPtyProcessJob.NativeFailure(
                "QueryInformationJobObject",6);
        WindowsPtyProcessJob job=WindowsPtyProcessJob.create(facade);
        job.claimSuspended(42);

        assertFalse(job.terminate(SHORT));

        assertEquals(0,facade.terminateCalls);
        assertFalse(facade.closed);
    }

    @Test void anUnexpectedNativeRuntimeFailureIsAlsoFailClosed(){
        FakeNative facade=new FakeNative();
        facade.accountingFailure=new IllegalStateException("simulated JNA mapping failure");
        WindowsPtyProcessJob job=WindowsPtyProcessJob.create(facade);
        job.claimSuspended(42);

        assertFalse(job.terminate(SHORT));

        assertFalse(facade.closed);
    }

    @Test void anUnknownCloseFailureRetainsTheEmptyJobAndCanBeRetried(){
        FakeNative facade=new FakeNative();
        facade.closeFailure=new WindowsPtyProcessJob.NativeFailure("CloseHandle job",6);
        WindowsPtyProcessJob job=WindowsPtyProcessJob.create(facade);
        job.claimSuspended(42);

        assertFalse(job.terminate(SHORT));
        assertFalse(facade.closed);

        facade.closeFailure=null;
        assertTrue(job.terminate(SHORT));
        assertTrue(facade.closed);
    }

    @Test void aFailedSuspendedAssignmentIsRememberedEvenThoughPty4jSwallowsCallbackErrors(){
        FakeNative facade=new FakeNative();
        facade.assignmentFailure=new WindowsPtyProcessJob.NativeFailure(
                "AssignProcessToJobObject",5);
        WindowsPtyProcessJob job=WindowsPtyProcessJob.create(facade);

        job.claimSuspended(42);

        IllegalStateException failure=assertThrows(IllegalStateException.class,
                ()->job.requireClaimed(42));
        assertEquals(facade.assignmentFailure,failure.getCause());
        assertEquals(42,facade.terminatedPid,
                "the suspended root must be terminated before pty4j resumes after a callback failure");
    }

    @Test void aDuplicateSuspendedCallbackTerminatesTheUnexpectedRootAndFailsTheClaim(){
        FakeNative facade=new FakeNative();
        WindowsPtyProcessJob job=WindowsPtyProcessJob.create(facade);
        job.claimSuspended(42);

        job.claimSuspended(43);

        assertEquals(43,facade.terminatedPid);
        assertThrows(IllegalStateException.class,()->job.requireClaimed(42));
    }

    @Test void killOnCloseConfigurationFailureDoesNotLeakTheNewJobHandle(){
        FakeNative facade=new FakeNative();
        facade.configureFailure=new WindowsPtyProcessJob.NativeFailure(
                "SetInformationJobObject",87);

        assertThrows(WindowsPtyProcessJob.NativeFailure.class,
                ()->WindowsPtyProcessJob.create(facade));

        assertTrue(facade.closed);
    }

    @Test void nativeStructuresMatchTheDocumentedWindowsX64JobLayouts(){
        assumeTrue(Native.POINTER_SIZE==8,"the distribution supports Windows x64");

        assertEquals(64,new WindowsPtyProcessJob.JobObjectBasicLimitInformation().size());
        assertEquals(144,new WindowsPtyProcessJob.JobObjectExtendedLimitInformation().size());
        assertEquals(48,new WindowsPtyProcessJob.JobObjectBasicAccountingInformation().size());
    }

    private static final class FakeNative implements WindowsPtyProcessJob.NativeFacade{
        private final Deque<Long> activeProcesses=new ArrayDeque<>();
        private int configureCalls;private int terminateCalls;private long assignedPid=-1;
        private long terminatedPid=-1;
        private boolean closed;
        private RuntimeException configureFailure;
        private RuntimeException assignmentFailure;
        private RuntimeException terminateFailure;
        private RuntimeException accountingFailure;
        private RuntimeException closeFailure;

        @Override public long createJob(){return 7;}
        @Override public void configureKillOnClose(long job){
            configureCalls++;
            if(configureFailure!=null)throw configureFailure;
        }
        @Override public void assignProcess(long job,long pid){
            assignedPid=pid;
            if(assignmentFailure!=null)throw assignmentFailure;
        }
        @Override public void terminateProcess(long pid){terminatedPid=pid;}
        @Override public void terminateJob(long job){
            terminateCalls++;
            if(terminateFailure!=null)throw terminateFailure;
        }
        @Override public long activeProcessCount(long job){
            if(accountingFailure!=null)throw accountingFailure;
            return activeProcesses.isEmpty()?0:activeProcesses.removeFirst();
        }
        @Override public void closeJob(long job){
            if(closeFailure!=null)throw closeFailure;
            closed=true;
        }
    }
}
