package dev.termestra.execution.application.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RunOutputHubTest {
    @Test void removesAFaultyListenerWithoutInterruptingOtherViewersOrThePtyCallback() {
        RunOutputHub hub = new RunOutputHub();
        AtomicInteger healthy = new AtomicInteger();
        hub.subscribe("run", 0, ignored -> { throw new IllegalStateException("viewer failed"); });
        hub.subscribe("run", 0, ignored -> healthy.incrementAndGet());

        assertDoesNotThrow(() -> hub.publish("run", 1, "one"));
        assertDoesNotThrow(() -> hub.publish("run", 2, "two"));

        assertEquals(2, healthy.get());
    }

    @Test void ignoresEventsAlreadyRepresentedByTheSnapshotAndDuplicateSequences() {
        RunOutputHub hub = new RunOutputHub();
        StringBuilder received = new StringBuilder();
        hub.subscribe("run", 5, received::append);

        hub.publish("run", 4, "old");
        hub.publish("run", 5, "snapshot");
        hub.publish("run", 6, "new");
        hub.publish("run", 6, "duplicate");
        hub.publish("run", 7, "er");

        assertEquals("newer", received.toString());
    }
}
