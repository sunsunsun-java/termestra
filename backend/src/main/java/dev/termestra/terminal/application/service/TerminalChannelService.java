package dev.termestra.terminal.application.service;

import dev.termestra.terminal.application.port.in.*;
import dev.termestra.terminal.application.port.out.TerminalRuntimeGateway;
import java.util.Objects;
import java.util.function.Consumer;

public final class TerminalChannelService implements TerminalChannelUseCase {
    private final TerminalRuntimeGateway runtime;
    public TerminalChannelService(TerminalRuntimeGateway runtime) { this.runtime = Objects.requireNonNull(runtime); }
    @Override public TerminalRunStatusView status(String runId) { return runtime.status(runId); }
    @Override public void input(String runId, byte[] input) { runtime.write(runId, input); }
    @Override public void resize(String runId, int columns, int rows) {
        if (columns <= 0 || rows <= 0) throw new IllegalArgumentException("terminal size must be positive");
        runtime.resize(runId, columns, rows);
    }
    @Override public void stop(String runId) { runtime.stop(runId); }
    @Override public void pauseOutput(String runId) { runtime.pauseOutput(runId); }
    @Override public void resumeOutput(String runId) { runtime.resumeOutput(runId); }
    @Override public TerminalOutputSession open(String runId, Consumer<String> output) {
        return runtime.open(runId, output);
    }
}
