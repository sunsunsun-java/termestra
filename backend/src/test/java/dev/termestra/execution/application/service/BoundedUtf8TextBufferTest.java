package dev.termestra.execution.application.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BoundedUtf8TextBufferTest {
    @Test void evictsAtUtf8CodePointBoundaries() {
        BoundedUtf8TextBuffer buffer = new BoundedUtf8TextBuffer(7);

        buffer.append("ab😀");
        buffer.append("界");

        assertEquals("😀界", buffer.toString());
        assertEquals(7, buffer.byteSize());
        assertFalse(Character.isLowSurrogate(buffer.toString().charAt(0)));
    }

    @Test void coalescesTinyPtyWritesIntoABoundedNumberOfChunks() {
        BoundedUtf8TextBuffer buffer = new BoundedUtf8TextBuffer(1_000_000);

        for (int index = 0; index < 1_100_000; index++) buffer.append("x");

        assertEquals(1_000_000, buffer.byteSize());
        assertEquals(1_000_000, buffer.toString().length());
        org.junit.jupiter.api.Assertions.assertTrue(buffer.chunkCount() <= 246);
    }
}
