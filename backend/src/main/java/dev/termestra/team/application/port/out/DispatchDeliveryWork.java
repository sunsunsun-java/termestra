package dev.termestra.team.application.port.out;

public record DispatchDeliveryWork(StoredDispatch dispatch, String runtimePort,
                                   String attemptId, int attemptCount) { }
