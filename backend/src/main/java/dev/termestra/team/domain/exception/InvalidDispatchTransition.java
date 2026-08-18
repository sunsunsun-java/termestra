package dev.termestra.team.domain.exception;

import dev.termestra.team.domain.model.DispatchStatus;

public final class InvalidDispatchTransition extends IllegalStateException {
    public InvalidDispatchTransition(DispatchStatus from, DispatchStatus to) {
        super("dispatch cannot transition from " + from.wireValue() + " to " + to.wireValue());
    }
}

