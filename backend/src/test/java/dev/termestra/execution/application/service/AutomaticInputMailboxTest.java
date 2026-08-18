package dev.termestra.execution.application.service;

import dev.termestra.execution.application.exception.ExecutionConflict;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AutomaticInputMailboxTest {
    @Test void closeRejectsEveryLaterSubmissionWithoutLeavingAWaiterBehind() {
        AutomaticInputMailbox mailbox = new AutomaticInputMailbox("run", (text, position) -> 1);
        mailbox.close();

        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertThrows(ExecutionConflict.class, () -> mailbox.submit("late")));
    }

    @Test void interruptedQueuedSubmissionIsKnownNotToHaveReachedThePty() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AutomaticInputMailbox mailbox = new AutomaticInputMailbox("run", (text, position) -> {
            firstStarted.countDown();
            try {
                releaseFirst.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return 1;
        });
        Thread first = Thread.ofVirtual().start(() -> mailbox.submit("first"));
        assertTrue(firstStarted.await(1, java.util.concurrent.TimeUnit.SECONDS));
        AtomicReference<InteractiveInputSubmitter.SubmissionException> failure = new AtomicReference<>();
        Thread second = Thread.ofVirtual().start(() -> {
            try {
                mailbox.submit("second");
            } catch (InteractiveInputSubmitter.SubmissionException error) {
                failure.set(error);
            }
        });

        second.interrupt();
        second.join(Duration.ofSeconds(1));
        releaseFirst.countDown();
        first.join(Duration.ofSeconds(1));
        mailbox.close();

        assertNotNull(failure.get());
        assertFalse(failure.get().inputAttempted());
    }

    @Test void interruptedInFlightSubmissionIsReportedAsUncertain() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AutomaticInputMailbox mailbox = new AutomaticInputMailbox("run", (text, position) -> {
            started.countDown();
            while (release.getCount() != 0) Thread.onSpinWait();
            return 1;
        });
        AtomicReference<InteractiveInputSubmitter.SubmissionException> failure = new AtomicReference<>();
        Thread submitter = Thread.ofVirtual().start(() -> {
            try {
                mailbox.submit("in flight");
            } catch (InteractiveInputSubmitter.SubmissionException error) {
                failure.set(error);
            }
        });
        assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS));

        submitter.interrupt();
        submitter.join(Duration.ofSeconds(1));
        release.countDown();
        mailbox.close();

        assertNotNull(failure.get());
        assertTrue(failure.get().inputAttempted());
    }
}
