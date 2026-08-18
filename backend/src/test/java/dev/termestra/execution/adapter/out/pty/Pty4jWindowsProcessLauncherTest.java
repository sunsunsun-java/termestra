package dev.termestra.execution.adapter.out.pty;

import dev.termestra.execution.application.port.out.ProcessLaunchRequest;
import dev.termestra.execution.application.port.out.PseudoTerminalHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class Pty4jWindowsProcessLauncherTest {
    @Test void naturalRootExitReapsAJobOwnedBackgroundChildBeforePublishingExit() throws Exception{
        String script="$child = Start-Process -PassThru -WindowStyle Hidden "
                +"-FilePath powershell.exe -ArgumentList '-NoLogo -NoProfile -NonInteractive "
                +"-Command \"Start-Sleep -Seconds 30\"'; "
                +"[Console]::WriteLine('TERMESTRA_CHILD_PID=' + $child.Id)";
        PseudoTerminalHandle handle=new Pty4jProcessLauncher().start(new ProcessLaunchRequest(
                List.of("powershell.exe","-NoLogo","-NoProfile","-NonInteractive","-Command",script),
                System.getProperty("java.io.tmpdir"),Map.of(),80,24));
        StringBuilder output=new StringBuilder();
        AtomicReference<Long> childPid=new AtomicReference<>();
        CountDownLatch childPublished=new CountDownLatch(1);
        CountDownLatch exited=new CountDownLatch(1);
        Pattern digits=Pattern.compile("TERMESTRA_CHILD_PID=(\\d+)");
        try{
            handle.activate(bytes->{
                synchronized(output){
                    output.append(new String(bytes,StandardCharsets.UTF_8));
                    var matcher=digits.matcher(output);
                    if(matcher.find()){
                        childPid.compareAndSet(null,Long.parseLong(matcher.group(1)));
                        childPublished.countDown();
                    }
                }
            },ignored->exited.countDown());

            assertTrue(childPublished.await(10,TimeUnit.SECONDS),
                    "fixture did not publish its Windows child pid");
            assertTrue(exited.await(10,TimeUnit.SECONDS),
                    "Job Object cleanup must finish before natural exit is published");
            assertTrue(ProcessHandle.of(childPid.get()).map(child->!child.isAlive()).orElse(true),
                    "natural root exit must terminate the job-owned background child");
        }finally{
            Long pid=childPid.get();
            if(pid!=null)ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                    .ifPresent(ProcessHandle::destroyForcibly);
            if(handle.alive())handle.stopAndConfirm();
        }
    }
}
