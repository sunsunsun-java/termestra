package dev.termestra.execution.application.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveOutputTailTest {
    @Test void retainsOnlyTheBoundedTailOfAFullTerminalHistory() {
        InteractiveOutputTail output = new InteractiveOutputTail();

        output.append("x".repeat(1_000_000));

        InteractiveOutputTail.Snapshot snapshot = output.snapshot();
        assertEquals(1_000_000, snapshot.position());
        assertEquals(InteractiveOutputTail.MAX_CHARS, snapshot.tail().length());
        assertTrue(snapshot.tail().chars().allMatch(value -> value == 'x'));
    }

    @Test void isolatesOutputProducedAfterTheCurrentPaste() {
        InteractiveOutputTail output = new InteractiveOutputTail();
        output.append("[Pasted text #7 +2 lines]\n❯");
        long baseline = output.snapshot().position();

        output.append("working\n[Pasted text #8 +3 lines]");

        String appended = output.snapshot().appendedSince(baseline);
        assertFalse(appended.contains("#7"));
        assertTrue(appended.contains("#8"));
    }

    @Test void keepsTheNewestPostBaselineOutputWhenAProducerOutrunsTheTail() {
        InteractiveOutputTail output = new InteractiveOutputTail();
        long baseline = output.snapshot().position();

        output.append("noise".repeat(2_000));
        output.append("[Pasted Content 12,345 chars]");

        String appended = output.snapshot().appendedSince(baseline);
        assertEquals(InteractiveOutputTail.MAX_CHARS, appended.length());
        assertTrue(appended.endsWith("[Pasted Content 12,345 chars]"));
    }

    @Test void preservesOrderAcrossRepeatedRingBufferWraps() {
        InteractiveOutputTail output = new InteractiveOutputTail();
        StringBuilder expected = new StringBuilder();

        for (int index = 0; index < 20_000; index++) {
            String value = Character.toString('a' + index % 26);
            output.append(value);
            expected.append(value);
        }

        assertEquals(expected.substring(expected.length() - InteractiveOutputTail.MAX_CHARS),
                output.snapshot().tail());
    }
}
