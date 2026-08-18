package dev.termestra.execution.application.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersistedMessageDeliveryTest {
    @Test void persistsBeforeDeliveryAndRollsBackTheExactMessageOnFailure() {
        List<String> events=new ArrayList<>();

        IllegalStateException failure=assertThrows(IllegalStateException.class,()->PersistedMessageDelivery.execute(
                ()->{events.add("persist");return 42;},
                sequence->events.add("rollback:"+sequence),
                ()->{events.add("deliver");throw new IllegalStateException("PTY closed");}));

        assertEquals("PTY closed",failure.getMessage());
        assertEquals(List.of("persist","deliver","rollback:42"),events);
    }

    @Test void keepsPersistedMessageAfterSuccessfulDelivery() throws InterruptedException {
        List<String> events=new ArrayList<>();

        PersistedMessageDelivery.execute(()->{events.add("persist");return 7;},
                sequence->events.add("rollback"),()->events.add("deliver"));

        assertEquals(List.of("persist","deliver"),events);
    }

    @Test void keepsPersistedMessageWhenTerminalInputMayAlreadyHaveBeenObserved() {
        List<String> events=new ArrayList<>();

        InteractiveInputSubmitter.SubmissionException failure=assertThrows(
                InteractiveInputSubmitter.SubmissionException.class,
                ()->PersistedMessageDelivery.execute(
                        ()->{events.add("persist");return 99;},
                        sequence->events.add("rollback:"+sequence),
                        ()->{events.add("deliver");throw new InteractiveInputSubmitter.SubmissionException(
                                "PTY closed after input write started",true);}));

        assertTrue(failure.inputAttempted());
        assertEquals(List.of("persist","deliver"),events);
    }

    @Test void rollsBackPersistedMessageWhenTypedFailureConfirmsNoInputWasWritten() {
        List<String> events=new ArrayList<>();

        InteractiveInputSubmitter.SubmissionException failure=assertThrows(
                InteractiveInputSubmitter.SubmissionException.class,
                ()->PersistedMessageDelivery.execute(
                        ()->{events.add("persist");return 100;},
                        sequence->events.add("rollback:"+sequence),
                        ()->{events.add("deliver");throw new InteractiveInputSubmitter.SubmissionException(
                                "Prompt never became ready",false);}));

        assertFalse(failure.inputAttempted());
        assertEquals(List.of("persist","deliver","rollback:100"),events);
    }

    @Test void preservesTheDeliveryFailureWhenRollbackAlsoFails() {
        IllegalStateException deliveryFailure=new IllegalStateException("PTY closed");
        IllegalStateException rollbackFailure=new IllegalStateException("database unavailable");

        IllegalStateException thrown=assertThrows(IllegalStateException.class,
                ()->PersistedMessageDelivery.execute(
                        ()->101,
                        sequence->{throw rollbackFailure;},
                        ()->{throw deliveryFailure;}));

        assertSame(deliveryFailure,thrown);
        assertArrayEquals(new Throwable[]{rollbackFailure},thrown.getSuppressed());
    }
}
