package dev.termestra.team.application.port.out;

public record DispatchEnqueueResult(String dispatchId, long messageSequence, boolean created) { }
