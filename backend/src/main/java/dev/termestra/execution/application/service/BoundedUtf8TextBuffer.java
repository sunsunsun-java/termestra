package dev.termestra.execution.application.service;

import java.util.ArrayDeque;
import java.util.Deque;

/** Bounded transcript that evicts only at Unicode code-point/UTF-8 boundaries. */
final class BoundedUtf8TextBuffer {
    private static final int TARGET_CHUNK_BYTES = 4 * 1024;

    private final int maximumBytes;
    private final Deque<Chunk> chunks = new ArrayDeque<>();
    private int bytes;

    BoundedUtf8TextBuffer(int maximumBytes) {
        if (maximumBytes <= 0) throw new IllegalArgumentException("maximumBytes must be positive");
        this.maximumBytes = maximumBytes;
    }

    void append(String value) {
        if (value == null || value.isEmpty()) return;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            int codePointBytes = utf8Bytes(codePoint);
            Chunk target = chunks.peekLast();
            if (target == null || !target.canAppend(codePointBytes)) {
                target = new Chunk();
                chunks.addLast(target);
            }
            target.append(codePoint, codePointBytes);
            bytes += codePointBytes;
            index += Character.charCount(codePoint);
            trim();
        }
    }

    private void trim() {
        while (bytes > maximumBytes && !chunks.isEmpty()) {
            Chunk first = chunks.peekFirst();
            int removed = first.evictAtLeast(bytes - maximumBytes);
            bytes -= removed;
            if (first.isEmpty()) chunks.removeFirst();
        }
    }

    @Override public String toString() {
        StringBuilder result = new StringBuilder(bytes);
        for (Chunk chunk : chunks) chunk.appendTo(result);
        return result.toString();
    }

    int byteSize() { return bytes; }

    int chunkCount() { return chunks.size(); }

    private static int utf8Bytes(int codePoint) {
        if (codePoint <= 0x7f) return 1;
        if (codePoint <= 0x7ff) return 2;
        if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
            // StandardCharsets.UTF_8 replaces an isolated UTF-16 surrogate with '?'.
            return 1;
        }
        if (codePoint <= 0xffff) return 3;
        return 4;
    }

    private static final class Chunk {
        private final StringBuilder text = new StringBuilder(TARGET_CHUNK_BYTES);
        private int start;
        private int bytes;

        boolean canAppend(int additionalBytes) {
            return start == 0 && bytes + additionalBytes <= TARGET_CHUNK_BYTES;
        }

        void append(int codePoint, int codePointBytes) {
            text.appendCodePoint(codePoint);
            bytes += codePointBytes;
        }

        int evictAtLeast(int minimumBytes) {
            int removed = 0;
            while (start < text.length() && removed < minimumBytes) {
                int codePoint = text.codePointAt(start);
                removed += utf8Bytes(codePoint);
                start += Character.charCount(codePoint);
            }
            bytes -= removed;
            return removed;
        }

        boolean isEmpty() { return start == text.length(); }

        void appendTo(StringBuilder destination) {
            destination.append(text, start, text.length());
        }
    }
}
