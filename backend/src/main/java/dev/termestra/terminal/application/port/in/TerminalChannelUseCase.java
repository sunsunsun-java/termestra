package dev.termestra.terminal.application.port.in;

import java.util.function.Consumer;

public interface TerminalChannelUseCase {
    TerminalRunStatusView status(String runId);
    void input(String runId, byte[] input);
    void resize(String runId, int columns, int rows);
    void stop(String runId);
    void pauseOutput(String runId);
    void resumeOutput(String runId);
    TerminalOutputSession open(String runId, Consumer<String> output);
}
