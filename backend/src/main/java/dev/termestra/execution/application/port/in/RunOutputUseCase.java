package dev.termestra.execution.application.port.in;

import java.util.function.Consumer;
public interface RunOutputUseCase { RunOutputSnapshot open(String runId, Consumer<String> listener); }
