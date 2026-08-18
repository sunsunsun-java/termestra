package dev.termestra.execution.adapter.out.pty;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import com.pty4j.windows.conpty.ConPtyLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

/**
 * Owns every Windows process descended from a PTY root by means of a kill-on-close Job Object.
 *
 * <p>pty4j's ConPTY launcher exposes the root pid while the process is still suspended. The job is
 * prepared before launch and the callback assigns that suspended root before pty4j resumes it, so
 * a fast root cannot fork and disappear before ownership is established. The job handle remains
 * open for the complete run; Windows then also terminates its members if the JVM exits abruptly.</p>
 */
final class WindowsPtyProcessJob {
    private static final Logger LOG=LoggerFactory.getLogger(WindowsPtyProcessJob.class);
    private static final long POLL_NANOS=Duration.ofMillis(10).toNanos();

    private final NativeFacade nativeFacade;
    private final long job;
    private boolean claimAttempted;
    private long claimedPid=-1;
    private NativeFailure claimFailure;
    private boolean closed;

    private WindowsPtyProcessJob(NativeFacade nativeFacade,long job){
        this.nativeFacade=nativeFacade;this.job=job;
    }

    static Optional<WindowsPtyProcessJob> prepareForCurrentPlatform(){
        if(!Platform.isWindows())return Optional.empty();
        // Without this preflight pty4j catches a ConPTY linkage failure and silently starts
        // winpty, whose already-running process cannot be assigned without a race.
        ConPtyLibrary.getInstance();
        return Optional.of(create(new JnaNativeFacade()));
    }

    static WindowsPtyProcessJob create(NativeFacade nativeFacade){
        long job=nativeFacade.createJob();
        try{
            nativeFacade.configureKillOnClose(job);
            return new WindowsPtyProcessJob(nativeFacade,job);
        }catch(RuntimeException|LinkageError configurationFailure){
            try{nativeFacade.closeJob(job);}
            catch(RuntimeException|LinkageError closeFailure){
                configurationFailure.addSuppressed(closeFailure);
            }
            throw configurationFailure;
        }
    }

    /** Called only by pty4j's pre-resume callback. Failures are retained because pty4j swallows them. */
    synchronized void claimSuspended(long pid){
        if(claimAttempted){
            claimFailure=new NativeFailure("duplicate suspended-process callback",0);
            terminateFailedClaim(pid,claimFailure);
            return;
        }
        claimAttempted=true;
        try{
            nativeFacade.assignProcess(job,pid);
            claimedPid=pid;
        }catch(RuntimeException|LinkageError failure){
            claimFailure=normalize("AssignProcessToJobObject",failure);
            terminateFailedClaim(pid,claimFailure);
        }
    }

    private void terminateFailedClaim(long pid,NativeFailure failure){
        try{nativeFacade.terminateProcess(pid);}
        catch(RuntimeException|LinkageError terminationFailure){
            failure.addSuppressed(terminationFailure);
        }
    }

    synchronized void requireClaimed(long expectedPid){
        if(claimFailure!=null){
            throw new IllegalStateException("Windows PTY process could not be assigned to its Job Object",
                    claimFailure);
        }
        if(!claimAttempted||claimedPid!=expectedPid){
            throw new IllegalStateException(
                    "Windows PTY did not run the suspended ownership callback for pid "+expectedPid);
        }
    }

    /**
     * Terminates the complete job and proves that no member remains before closing the handle.
     * Unknown native states deliberately retain the handle and return false so callers cannot
     * release run credentials or capacity and can retry through their bounded supervisor.
     */
    synchronized boolean terminate(Duration timeout){
        requirePositive(timeout,"timeout");
        if(closed)return true;
        boolean[] interrupted={Thread.interrupted()};
        try{
            long active=activeProcessCount();
            if(active<0)return false;
            if(active>0){
                try{nativeFacade.terminateJob(job);}
                catch(RuntimeException|LinkageError failure){
                    NativeFailure nativeFailure=normalize("TerminateJobObject",failure);
                    LOG.warn("Could not terminate owned Windows PTY Job Object (operation "
                            +nativeFailure.operation()+", error "+nativeFailure.errorCode()+")",
                            nativeFailure);
                    return false;
                }
                if(!awaitEmpty(timeout,interrupted))return false;
            }
            try{
                nativeFacade.closeJob(job);
                closed=true;
                return true;
            }catch(RuntimeException|LinkageError failure){
                NativeFailure nativeFailure=normalize("CloseHandle job",failure);
                LOG.warn("Could not close empty Windows PTY Job Object (operation "
                        +nativeFailure.operation()+", error "+nativeFailure.errorCode()+")",
                        nativeFailure);
                return false;
            }
        }finally{
            if(interrupted[0]||Thread.interrupted())Thread.currentThread().interrupt();
        }
    }

    /** Startup-error cleanup: closing a configured handle invokes Windows' kill-on-close contract. */
    synchronized boolean abort(Duration timeout){
        if(terminate(timeout))return true;
        if(closed)return true;
        try{
            nativeFacade.closeJob(job);
            closed=true;
            return true;
        }catch(RuntimeException|LinkageError failure){
            NativeFailure nativeFailure=normalize("CloseHandle failed-start job",failure);
            LOG.error("Could not close failed-start Windows PTY Job Object (operation "
                    +nativeFailure.operation()+", error "+nativeFailure.errorCode()+")",
                    nativeFailure);
            return false;
        }
    }

    private boolean awaitEmpty(Duration timeout,boolean[] interrupted){
        long deadline=saturatedDeadline(timeout);
        while(true){
            long active=activeProcessCount();
            if(active<0)return false;
            if(active==0)return true;
            if(System.nanoTime()-deadline>=0)return false;
            LockSupport.parkNanos(Math.min(POLL_NANOS,
                    Math.max(1,deadline-System.nanoTime())));
            if(Thread.interrupted())interrupted[0]=true;
        }
    }

    private long activeProcessCount(){
        try{return nativeFacade.activeProcessCount(job);}
        catch(RuntimeException|LinkageError failure){
            NativeFailure nativeFailure=normalize("QueryInformationJobObject",failure);
            LOG.warn("Could not verify owned Windows PTY Job Object membership (operation "
                    +nativeFailure.operation()+", error "+nativeFailure.errorCode()+")",
                    nativeFailure);
            return -1;
        }
    }

    private static NativeFailure normalize(String operation,Throwable failure){
        if(failure instanceof NativeFailure nativeFailure)return nativeFailure;
        return new NativeFailure(operation,-1,failure);
    }

    private static long saturatedDeadline(Duration timeout){
        long now=System.nanoTime();long nanos=timeout.toNanos();long deadline=now+nanos;
        return deadline<now?Long.MAX_VALUE:deadline;
    }

    private static void requirePositive(Duration value,String name){
        if(value==null||value.isZero()||value.isNegative())
            throw new IllegalArgumentException(name+" must be positive");
    }

    interface NativeFacade{
        long createJob();
        void configureKillOnClose(long job);
        void assignProcess(long job,long pid);
        void terminateProcess(long pid);
        void terminateJob(long job);
        long activeProcessCount(long job);
        void closeJob(long job);
    }

    static final class NativeFailure extends RuntimeException{
        private final String operation;private final int errorCode;
        NativeFailure(String operation,int errorCode){
            super(operation+" failed with Windows error "+errorCode);
            this.operation=operation;this.errorCode=errorCode;
        }
        NativeFailure(String operation,int errorCode,Throwable cause){
            super(operation+" failed with Windows error "+errorCode,cause);
            this.operation=operation;this.errorCode=errorCode;
        }
        String operation(){return operation;}
        int errorCode(){return errorCode;}
    }

    private static final class JnaNativeFacade implements NativeFacade{
        private static final int JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION=1;
        private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION=9;
        private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE=0x00002000;
        private static final int PROCESS_TERMINATE=0x0001;
        private static final int PROCESS_SET_QUOTA=0x0100;
        private static final int OWNERSHIP_PROCESS_ACCESS=PROCESS_TERMINATE|PROCESS_SET_QUOTA;
        private static final int OWNERSHIP_FAILURE_EXIT_CODE=1;

        private final Kernel32JobApi api=Kernel32JobApi.INSTANCE;

        @Override public long createJob(){
            WinNT.HANDLE handle=api.CreateJobObjectW(null,null);
            if(invalid(handle))throw failure("CreateJobObjectW");
            return Pointer.nativeValue(handle.getPointer());
        }

        @Override public void configureKillOnClose(long job){
            JobObjectExtendedLimitInformation information=new JobObjectExtendedLimitInformation();
            information.basicLimitInformation.limitFlags=JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            information.write();
            if(!api.SetInformationJobObject(handle(job),JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                    information,information.size()))throw failure("SetInformationJobObject");
        }

        @Override public void assignProcess(long job,long pid){
            WinNT.HANDLE process=openProcess(pid,OWNERSHIP_PROCESS_ACCESS);
            if(invalid(process))throw failure("OpenProcess");
            NativeFailure assignmentFailure=null;
            try{
                if(!api.AssignProcessToJobObject(handle(job),process)){
                    assignmentFailure=failure("AssignProcessToJobObject");
                }
            }finally{
                if(!api.CloseHandle(process)){
                    NativeFailure closeFailure=failure("CloseHandle process");
                    if(assignmentFailure==null)assignmentFailure=closeFailure;
                    else assignmentFailure.addSuppressed(closeFailure);
                }
            }
            if(assignmentFailure!=null)throw assignmentFailure;
        }

        @Override public void terminateProcess(long pid){
            WinNT.HANDLE process=openProcess(pid,PROCESS_TERMINATE);
            if(invalid(process))throw failure("OpenProcess for termination");
            NativeFailure terminationFailure=null;
            try{
                if(!api.TerminateProcess(process,OWNERSHIP_FAILURE_EXIT_CODE))
                    terminationFailure=failure("TerminateProcess");
            }finally{
                if(!api.CloseHandle(process)){
                    NativeFailure closeFailure=failure("CloseHandle process after termination");
                    if(terminationFailure==null)terminationFailure=closeFailure;
                    else terminationFailure.addSuppressed(closeFailure);
                }
            }
            if(terminationFailure!=null)throw terminationFailure;
        }

        @Override public void terminateJob(long job){
            if(!api.TerminateJobObject(handle(job),OWNERSHIP_FAILURE_EXIT_CODE))
                throw failure("TerminateJobObject");
        }

        @Override public long activeProcessCount(long job){
            JobObjectBasicAccountingInformation information=
                    new JobObjectBasicAccountingInformation();
            if(!api.QueryInformationJobObject(handle(job),JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION,
                    information,information.size(),null))
                throw failure("QueryInformationJobObject");
            information.read();
            return Integer.toUnsignedLong(information.activeProcesses);
        }

        @Override public void closeJob(long job){
            if(!api.CloseHandle(handle(job)))throw failure("CloseHandle job");
        }

        private static WinNT.HANDLE handle(long value){
            return new WinNT.HANDLE(Pointer.createConstant(value));
        }
        private WinNT.HANDLE openProcess(long pid,int access){
            if(pid<=0||pid>0xffff_ffffL)throw new NativeFailure("invalid process id",87);
            return api.OpenProcess(access,false,(int)pid);
        }
        private static boolean invalid(WinNT.HANDLE handle){
            return handle==null||handle.getPointer()==null
                    ||Pointer.nativeValue(handle.getPointer())==0
                    ||WinBase.INVALID_HANDLE_VALUE.equals(handle);
        }
        private static NativeFailure failure(String operation){
            return new NativeFailure(operation,Native.getLastError());
        }
    }

    private interface Kernel32JobApi extends StdCallLibrary{
        Kernel32JobApi INSTANCE=Native.load("kernel32",Kernel32JobApi.class,
                W32APIOptions.DEFAULT_OPTIONS);
        WinNT.HANDLE CreateJobObjectW(WinBase.SECURITY_ATTRIBUTES attributes,WString name);
        boolean SetInformationJobObject(WinNT.HANDLE job,int informationClass,
                                        Structure information,int informationLength);
        WinNT.HANDLE OpenProcess(int access,boolean inheritHandle,int pid);
        boolean AssignProcessToJobObject(WinNT.HANDLE job,WinNT.HANDLE process);
        boolean TerminateProcess(WinNT.HANDLE process,int exitCode);
        boolean TerminateJobObject(WinNT.HANDLE job,int exitCode);
        boolean QueryInformationJobObject(WinNT.HANDLE job,int informationClass,
                                          Structure information,int informationLength,
                                          IntByReference returnedLength);
        boolean CloseHandle(WinNT.HANDLE handle);
    }

    @Structure.FieldOrder({"perProcessUserTimeLimit","perJobUserTimeLimit","limitFlags",
            "minimumWorkingSetSize","maximumWorkingSetSize","activeProcessLimit","affinity",
            "priorityClass","schedulingClass"})
    public static final class JobObjectBasicLimitInformation extends Structure{
        public long perProcessUserTimeLimit;
        public long perJobUserTimeLimit;
        public int limitFlags;
        public BaseTSD.SIZE_T minimumWorkingSetSize=new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T maximumWorkingSetSize=new BaseTSD.SIZE_T();
        public int activeProcessLimit;
        public BaseTSD.ULONG_PTR affinity=new BaseTSD.ULONG_PTR();
        public int priorityClass;
        public int schedulingClass;
        public JobObjectBasicLimitInformation(){super(ALIGN_MSVC);}
    }

    @Structure.FieldOrder({"basicLimitInformation","ioInfo","processMemoryLimit",
            "jobMemoryLimit","peakProcessMemoryUsed","peakJobMemoryUsed"})
    public static final class JobObjectExtendedLimitInformation extends Structure{
        public JobObjectBasicLimitInformation basicLimitInformation=
                new JobObjectBasicLimitInformation();
        public WinNT.IO_COUNTERS ioInfo=new WinNT.IO_COUNTERS();
        public BaseTSD.SIZE_T processMemoryLimit=new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T jobMemoryLimit=new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T peakProcessMemoryUsed=new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T peakJobMemoryUsed=new BaseTSD.SIZE_T();
        public JobObjectExtendedLimitInformation(){super(ALIGN_MSVC);}
    }

    @Structure.FieldOrder({"totalUserTime","totalKernelTime","thisPeriodTotalUserTime",
            "thisPeriodTotalKernelTime","totalPageFaultCount","totalProcesses",
            "activeProcesses","totalTerminatedProcesses"})
    public static final class JobObjectBasicAccountingInformation extends Structure{
        public long totalUserTime;
        public long totalKernelTime;
        public long thisPeriodTotalUserTime;
        public long thisPeriodTotalKernelTime;
        public int totalPageFaultCount;
        public int totalProcesses;
        public int activeProcesses;
        public int totalTerminatedProcesses;
        public JobObjectBasicAccountingInformation(){super(ALIGN_MSVC);}
    }
}
