package dev.termestra.execution.application.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IncrementalUtf8DecoderTest {
    @Test void preservesACharacterSplitAcrossPtyChunks() {
        byte[] value = "终端".getBytes(StandardCharsets.UTF_8);
        IncrementalUtf8Decoder decoder = new IncrementalUtf8Decoder();
        assertEquals("", decoder.decode(java.util.Arrays.copyOfRange(value, 0, 2)));
        assertEquals("终端", decoder.decode(java.util.Arrays.copyOfRange(value, 2, value.length)));
    }

    @Test void flushesAnIncompleteFinalSequenceAsAReplacementCharacter() {
        IncrementalUtf8Decoder decoder = new IncrementalUtf8Decoder();

        assertEquals("", decoder.decode(new byte[]{(byte) 0xe7, (byte) 0xbb}));
        assertEquals("�", decoder.finish());
    }
}
