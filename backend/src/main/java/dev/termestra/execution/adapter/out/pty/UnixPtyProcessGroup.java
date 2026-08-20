package dev.termestra.execution.adapter.out.pty;

import com.pty4j.PtyProcess;
import com.pty4j.unix.PtyHelpers;
import com.pty4j.unix.UnixPtyProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

/**
 * Owns the dedicated POSIX process group created by pty4j for a Unix PTY.
 *
 * <p>The claim is made only while the root is alive and only when signal zero confirms a process
 * group whose id is exactly the root pid. That proves the root is the group leader without parsing
 * platform-specific process listings. Retaining the id then lets us reap group members even after
 * the root exits and their parent relationship is lost.</p>
 */
final class UnixPtyProcessGroup {
    private static final Logger LOG=LoggerFactory.getLogger(UnixPtyProcessGroup.class);
    private static final int EPERM=1;
    private static final int ESRCH=3;
    private static final long POLL_NANOS=Duration.ofMillis(10).toNanos();

    private final int groupId;
    private final PtyHelpers.OSFacade os;
    private boolean gone;

    UnixPtyProcessGroup(int groupId,PtyHelpers.OSFacade os){
        this.groupId=groupId;this.os=os;
    }

    static Optional<UnixPtyProcessGroup> claim(PtyProcess process){
        if(!(process instanceof UnixPtyProcess))return Optional.empty();
        long pid=process.pid();
        if(pid<=0||pid>Integer.MAX_VALUE)return Optional.empty();
        try{
            PtyHelpers.OSFacade os=PtyHelpers.getInstance();
            int groupId=(int)pid;
            if(os.killpg(groupId,0)!=0&&os.errno()!=EPERM){
                LOG.warn("Unix PTY pid {} is not the leader of a signalable dedicated process group; using descendant tracking only",pid);
                return Optional.empty();
            }
            return Optional.of(new UnixPtyProcessGroup(groupId,os));
        }catch(RuntimeException|LinkageError unavailable){
            LOG.warn("Unix PTY process-group ownership is unavailable; using descendant tracking only",unavailable);
            return Optional.empty();
        }
    }

    synchronized boolean terminate(Duration gracefulTimeout,Duration forcedTimeout){
        if(gone)return true;
        boolean[] interrupted={Thread.interrupted()};
        try{
            Presence initial=presence();
            if(initial==Presence.GONE){gone=true;return true;}
            if(initial==Presence.UNKNOWN)return false;
            if(!signal(PtyHelpers.SIGTERM))return gone;
            if(awaitGone(gracefulTimeout,interrupted))return true;
            if(!signal(PtyHelpers.SIGKILL))return gone;
            return awaitGone(forcedTimeout,interrupted);
        }finally{
            if(interrupted[0]||Thread.interrupted())Thread.currentThread().interrupt();
        }
    }

    private boolean signal(int signal){
        if(os.killpg(groupId,signal)==0)return true;
        int error=os.errno();
        if(error==ESRCH){gone=true;return false;}
        if(error==EPERM){
            // macOS can report EPERM when an owned group contains only processes that are no
            // longer signalable (for example a short-lived child awaiting reaping). Keep polling
            // for bounded ESRCH confirmation. Persistent EPERM still exhausts the deadline and
            // fails closed, so an unrelated or inaccessible group is never treated as terminated.
            LOG.debug("Could not signal owned Unix PTY process group {} (signal {}, errno {}); awaiting bounded disappearance",
                    groupId,signal,error);
            return true;
        }
        LOG.warn("Could not signal owned Unix PTY process group {} (signal {}, errno {})",
                groupId,signal,error);
        return false;
    }

    private boolean awaitGone(Duration timeout,boolean[] interrupted){
        long deadline=saturatedDeadline(timeout);
        while(true){
            Presence state=presence();
            if(state==Presence.GONE){gone=true;return true;}
            if(state==Presence.UNKNOWN)return false;
            if(System.nanoTime()-deadline>=0)return false;
            LockSupport.parkNanos(Math.min(POLL_NANOS,
                    Math.max(1,deadline-System.nanoTime())));
            if(Thread.interrupted())interrupted[0]=true;
        }
    }

    private Presence presence(){
        if(os.killpg(groupId,0)==0)return Presence.PRESENT;
        int error=os.errno();
        // POSIX/macOS define EPERM for a process group that exists but contains a member the
        // caller may not signal. It is therefore positive existence evidence, not an unknown
        // lookup failure. The actual SIGTERM/SIGKILL call below still has to succeed.
        if(error==EPERM)return Presence.PRESENT;
        if(error==ESRCH)return Presence.GONE;
        LOG.warn("Could not verify owned Unix PTY process group {} (errno {})",groupId,error);
        return Presence.UNKNOWN;
    }

    private static long saturatedDeadline(Duration timeout){
        long now=System.nanoTime();long nanos=timeout.toNanos();long deadline=now+nanos;
        return deadline<now?Long.MAX_VALUE:deadline;
    }

    private enum Presence{PRESENT,GONE,UNKNOWN}
}
