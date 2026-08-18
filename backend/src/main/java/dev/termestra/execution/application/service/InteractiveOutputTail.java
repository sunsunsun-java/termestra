package dev.termestra.execution.application.service;

/**
 * Bounded output projection used only for interactive prompt and paste acknowledgement detection.
 * The retained terminal transcript remains owned by the run detail model.
 */
final class InteractiveOutputTail {
    static final int MAX_CHARS = 8_192;

    private final char[] tail = new char[MAX_CHARS];
    private int start;
    private int size;
    private long position;

    synchronized void append(String value) {
        if (value == null || value.isEmpty()) return;
        position += value.length();
        if (value.length() >= MAX_CHARS) {
            value.getChars(value.length() - MAX_CHARS, value.length(), tail, 0);
            start = 0;
            size = MAX_CHARS;
            return;
        }

        int writeAt = (start + size) % MAX_CHARS;
        int firstCopy = Math.min(value.length(), MAX_CHARS - writeAt);
        value.getChars(0, firstCopy, tail, writeAt);
        if (firstCopy < value.length()) {
            value.getChars(firstCopy, value.length(), tail, 0);
        }
        int overflow = Math.max(0, size + value.length() - MAX_CHARS);
        start = (start + overflow) % MAX_CHARS;
        size = Math.min(MAX_CHARS, size + value.length());
    }

    synchronized Snapshot snapshot() {
        char[] copy = new char[size];
        int firstCopy = Math.min(size, MAX_CHARS - start);
        System.arraycopy(tail, start, copy, 0, firstCopy);
        if (firstCopy < size) {
            System.arraycopy(tail, 0, copy, firstCopy, size - firstCopy);
        }
        return new Snapshot(position, new String(copy));
    }

    record Snapshot(long position, String tail) {
        String appendedSince(long baseline) {
            long tailStart = position - tail.length();
            if (baseline <= tailStart) return tail;
            if (baseline >= position) return "";
            return tail.substring((int) (baseline - tailStart));
        }
    }
}
