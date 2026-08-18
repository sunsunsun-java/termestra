package dev.termestra.terminal.application.port.out;

import dev.termestra.terminal.application.port.in.TerminalOutputSession;
import dev.termestra.terminal.application.port.in.TerminalRunStatusView;
import java.util.function.Consumer;

public interface TerminalRuntimeGateway {
    TerminalRunStatusView status(String runId);
    void write(String runId, byte[] input);
    void resize(String runId, int columns, int rows);
    void stop(String runId);
    void pauseOutput(String runId);
    void resumeOutput(String runId);
    TerminalOutputSession open(String runId, Consumer<String> output);
}
