package dev.termestra.execution.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.execution.adapter.out.pty.Pty4jProcessLauncher;
import dev.termestra.execution.adapter.out.session.FilesystemAgentSessionCapture;
import dev.termestra.execution.adapter.out.terminal.VtPromptTerminal;
import dev.termestra.execution.application.port.in.StartAgentCommand;
import dev.termestra.execution.application.port.out.*;
import dev.termestra.execution.domain.model.AgentLaunchConfiguration;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentExecutionRecoveryTest {
    private static final String SESSION="11111111-1111-4111-8111-111111111111";
    @TempDir Path temporaryDirectory;

    static Stream<List<String>> codexConfigurationArguments(){
        return Stream.of(List.of("-c","model_reasoning_effort=high"),
                List.of("--config","model_reasoning_effort=high"),
                List.of("-s","workspace-write"),List.of("--sandbox","workspace-write"),
                List.of("-p","resume"));
    }

    @ParameterizedTest @MethodSource("codexConfigurationArguments")
    void codexConfigurationFlagsDoNotSuppressNativeResume(List<String> arguments)throws Exception{
        FakePty pty=new FakePty();
        AtomicReference<ProcessLaunchRequest> launched=new AtomicReference<>();
        var configuration=configuration("codex",arguments,null,"resume {session_id}");
        try(var service=service(configuration,request->{launched.set(request);return pty;})){
            var run=service.start(start());
            awaitRunning(service,run.runId());
            assertEquals(List.of("codex","resume",SESSION),launched.get().command().subList(0,3));
            assertEquals(arguments,launched.get().command().subList(3,launched.get().command().size()));
            assertTrue(pty.writes.isEmpty(),"a genuinely resumed session must not receive duplicate initialization");
        }
    }

    @Test void codexExistingResumeAfterGlobalOptionsIsNotDuplicated()throws Exception{
        List<String> arguments=List.of("--dangerously-bypass-approvals-and-sandbox","-c",
                "model_reasoning_effort=high","resume",SESSION);
        FakePty pty=new FakePty();
        AtomicReference<ProcessLaunchRequest> launched=new AtomicReference<>();
        try(var service=service(configuration("codex",arguments,null,"resume {session_id}"),
                request->{launched.set(request);return pty;})){
            var run=service.start(start());
            awaitRunning(service,run.runId());
            assertEquals(arguments,launched.get().command().subList(1,launched.get().command().size()));
            assertTrue(pty.writes.isEmpty());
        }
    }

    @Test void claudeContinueStillSuppressesDuplicateResume()throws Exception{
        FakePty pty=new FakePty();
        AtomicReference<ProcessLaunchRequest> launched=new AtomicReference<>();
        try(var service=service(configuration("claude",List.of("-c"),null,"--resume {session_id}"),
                request->{launched.set(request);return pty;})){
            var run=service.start(start());
            awaitRunning(service,run.runId());
            assertEquals(List.of("claude","-c"),launched.get().command());
            assertTrue(pty.writes.isEmpty());
        }
    }

    @Test @EnabledOnOs({OS.MAC,OS.LINUX})
    void shellStartupWithSavedSessionKeepsItsCommandAndReceivesRecoveryInput()throws Exception{
        String script="printf '\\033[2J\\033[H❯ '; cat";
        var configuration=configuration("/bin/sh",List.of("-c",script),"codex","resume {session_id}");
        AtomicReference<ProcessLaunchRequest> launched=new AtomicReference<>();
        Pty4jProcessLauncher launcher=new Pty4jProcessLauncher();
        try(var service=service(configuration,request->{launched.set(request);return launcher.start(request);})){
            var run=service.start(start());
            awaitRunning(service,run.runId());
            assertEquals(List.of("/bin/sh","-c",script),launched.get().command());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!service.get(run.runId()).output().contains("Termestra session binding:")
                    &&System.nanoTime()<deadline)Thread.sleep(10);
            assertTrue(service.get(run.runId()).output().contains("Termestra session binding:"),
                    "wrapper startup must receive the fallback input even when an old provider session exists");
            service.stop(run.runId());
            assertEquals("error",service.get(run.runId()).status(),"PTY teardown must finish durably");
        }
    }

    @ParameterizedTest @ValueSource(ints={0,1})
    void fallbackSessionCanBeCapturedEvenWhenItsSummaryIsTruncated(int oversized)throws Exception{
        int messageLength=oversized==0?0:AutomaticPromptLimits.MAX_AUTOMATIC_PROMPT_CHARACTERS+20_000;
        AgentDescriptor agent=agent();
        var message=new AgentRecoveryContextProvider.RecoveryMessage("user_input",null,
                agent.agentId(),"x".repeat(messageLength),null);
        String summary=AgentRecoverySummary.build(agent,new AgentRecoveryContextProvider.RecoveryContext(
                "",List.of(message),List.of(),List.of()));
        ObjectMapper json=new ObjectMapper();
        Path home=Files.createDirectories(temporaryDirectory.resolve("codex-home"));
        Path day=Files.createDirectories(home.resolve("sessions/2026/09/12"));
        var capture=new FilesystemAgentSessionCapture(json);
        String config=json.writeValueAsString(Map.of("source","codex_session_jsonl_dir",
                "pattern",home+"/sessions/**/*.jsonl"));
        var before=capture.snapshot(agent,config).orElseThrow();
        String header=json.writeValueAsString(Map.of("payload",Map.of("cwd",agent.workspacePath(),"id",SESSION)));
        Files.writeString(day.resolve("rollout-recovery.jsonl"),header+"\n"
                +json.writeValueAsString(Map.of("text",summary))+"\n");
        assertTrue(summary.length()<=AutomaticPromptLimits.MAX_AUTOMATIC_PROMPT_CHARACTERS);
        assertEquals(Optional.of(SESSION),capture.findNew(before),
                "the recovery message must bind the newly created session to the agent");
    }

    private AgentExecutionService service(AgentLaunchConfiguration configuration,PseudoTerminalLauncher launcher){
        AgentExecutionRepository repository=mock(AgentExecutionRepository.class);
        when(repository.findConfiguration("workspace","agent")).thenReturn(Optional.of(configuration));
        when(repository.findLastSession("workspace","agent")).thenReturn(Optional.of(SESSION));
        when(repository.insertRun(anyString(),anyString(),anyString(),anyLong(),any(),any())).thenReturn(true);
        when(repository.markRunning(anyString(),any())).thenReturn(true);
        when(repository.finishRun(anyString(),any(),any(),any(),anyString(),anyString(),any())).thenReturn(true);
        AgentSessionCapture capture=mock(AgentSessionCapture.class);
        when(capture.snapshot(any(),any())).thenReturn(Optional.empty());
        AgentCredentialIssuer credentials=mock(AgentCredentialIssuer.class);
        when(credentials.issue(anyString())).thenReturn("test-token");
        AgentRecoveryContextProvider recovery=mock(AgentRecoveryContextProvider.class);
        when(recovery.hasPreviousRun(anyString(),anyString())).thenReturn(true);
        when(recovery.load(anyString(),any())).thenReturn(new AgentRecoveryContextProvider.RecoveryContext(
                "",List.of(),List.of(),List.of()));
        return new AgentExecutionService(repository,(workspace,id)->Optional.of(agent()),credentials,
                launcher,capture,(preset,command)->List.of(),recovery,VtPromptTerminal::new,
                Clock.systemUTC(),new RuntimeOperationCoordinator());
    }

    private AgentDescriptor agent(){return new AgentDescriptor("workspace","Recovery",
            temporaryDirectory.toString(),"agent","Alice","Review","coder");}
    private static StartAgentCommand start(){return new StartAgentCommand("workspace","agent","4010");}
    private static AgentLaunchConfiguration configuration(String command,List<String> arguments,
                                                          String interactiveCommand,String resume){
        return new AgentLaunchConfiguration(command,arguments,null,interactiveCommand,true,resume,null,Map.of());
    }
    private static void awaitRunning(AgentExecutionService service,String runId)throws InterruptedException{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while("starting".equals(service.get(runId).status())&&System.nanoTime()<deadline)Thread.sleep(10);
        assertEquals("running",service.get(runId).status(),service.get(runId).startupMessage());
    }
    private static final class FakePty implements PseudoTerminalHandle{
        private volatile boolean alive=true;
        private final List<String> writes=new CopyOnWriteArrayList<>();
        public long pid(){return 123;}
        public void activate(Consumer<byte[]> output,IntConsumer exit){output.accept("❯ ".getBytes(StandardCharsets.UTF_8));}
        public void write(byte[] input){writes.add(new String(input,StandardCharsets.UTF_8));}
        public void resize(int columns,int rows){}
        public void pauseOutput(){}
        public void resumeOutput(){}
        public void stop(){alive=false;}
        public boolean alive(){return alive;}
    }
}
