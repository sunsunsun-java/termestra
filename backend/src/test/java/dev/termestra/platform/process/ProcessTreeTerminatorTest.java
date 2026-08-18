package dev.termestra.platform.process;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessTreeTerminatorTest {
    @Test void descendantOverflowCanNeverBeReportedAsConfirmedTermination() throws Exception {
        Process process=new ProcessBuilder("/bin/sh","-c",
                "sleep 30 & sleep 30 & sleep 30 & wait").start();
        List<ProcessHandle> allChildren=List.of();
        try{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(System.nanoTime()<deadline){
                try(var descendants=ProcessHandle.of(process.pid()).stream()
                        .flatMap(ProcessHandle::descendants)){
                    allChildren=descendants.toList();
                }
                if(allChildren.size()>=3)break;
                Thread.sleep(10);
            }
            assertTrue(allChildren.size()>=3,"fixture did not create three descendants");

            ProcessTreeTerminator.TrackedProcessTree tree=ProcessTreeTerminator.track(process);
            boolean confirmed=tree.terminate(
                    Duration.ofMillis(50),Duration.ofMillis(100),2);

            assertFalse(confirmed,
                    "an untracked third descendant must prevent complete-tree confirmation");
        }finally{
            process.destroyForcibly();
            for(ProcessHandle child:allChildren)if(child.isAlive())child.destroyForcibly();
            process.waitFor(2,TimeUnit.SECONDS);
        }
    }
}
