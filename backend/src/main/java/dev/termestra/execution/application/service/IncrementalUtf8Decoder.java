package dev.termestra.execution.application.service;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class IncrementalUtf8Decoder {
    private final java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
    private byte[] remainder = new byte[0];

    synchronized String decode(byte[] input) {
        byte[] combined = new byte[remainder.length + input.length];
        System.arraycopy(remainder, 0, combined, 0, remainder.length);
        System.arraycopy(input, 0, combined, remainder.length, input.length);
        ByteBuffer bytes = ByteBuffer.wrap(combined);
        CharBuffer characters = CharBuffer.allocate(Math.max(1, combined.length));
        decoder.decode(bytes, characters, false);
        remainder = new byte[bytes.remaining()];
        bytes.get(remainder);
        characters.flip();
        return characters.toString();
    }

    synchronized String finish() {
        ByteBuffer bytes = ByteBuffer.wrap(remainder);
        CharBuffer characters = CharBuffer.allocate(Math.max(1, remainder.length + 1));
        decoder.decode(bytes, characters, true);
        decoder.flush(characters);
        decoder.reset();
        remainder = new byte[0];
        characters.flip();
        return characters.toString();
    }
}
