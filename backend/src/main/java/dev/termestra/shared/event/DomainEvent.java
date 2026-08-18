package dev.termestra.shared.event;

import java.time.Instant;
import java.util.UUID;

public interface DomainEvent {
    UUID eventId();

    Instant occurredAt();
}

