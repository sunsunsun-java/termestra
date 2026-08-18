package dev.termestra.team.application.port.in;

public record TeamOperationResult(String dispatchId, boolean forwarded, String forwardError) { }
