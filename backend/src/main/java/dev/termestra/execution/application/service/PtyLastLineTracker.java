package dev.termestra.execution.application.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a small, incremental projection of the current PTY line for list views.
 * It intentionally does not retain or replay terminal history.
 */
final class PtyLastLineTracker {
    private static final int MAX_CELLS = 240;
    private final List<String> cells = new ArrayList<>();
    private final StringBuilder sequence = new StringBuilder();
    private State state = State.TEXT;
    private int cursor;
    private int savedCursor;
    private String lastNonEmptyLine;

    private enum State { TEXT, ESCAPE, CSI, OSC, OSC_ESCAPE }

    void write(String text) {
        text.codePoints().forEach(this::accept);
    }

    String lastLine() {
        String current = render().strip();
        return current.isEmpty() ? lastNonEmptyLine : current;
    }

    private void accept(int codePoint) {
        if (state == State.OSC) {
            if (codePoint == 7) state = State.TEXT;
            else if (codePoint == 27) state = State.OSC_ESCAPE;
            return;
        }
        if (state == State.OSC_ESCAPE) {
            state = codePoint == '\\' ? State.TEXT : State.OSC;
            return;
        }
        if (state == State.ESCAPE) {
            if (codePoint == '[') {
                state = State.CSI;
                sequence.setLength(0);
            } else if (codePoint == ']') {
                state = State.OSC;
            } else {
                escape(codePoint);
                state = State.TEXT;
            }
            return;
        }
        if (state == State.CSI) {
            if (codePoint >= 0x40 && codePoint <= 0x7e) {
                csi(codePoint, sequence.toString());
                sequence.setLength(0);
                state = State.TEXT;
            } else if (sequence.length() < 64) {
                sequence.appendCodePoint(codePoint);
            }
            return;
        }
        if (codePoint == 27) {
            state = State.ESCAPE;
        } else if (codePoint == '\r') {
            cursor = 0;
        } else if (codePoint == '\n') {
            commitAndClear();
        } else if (codePoint == '\b') {
            cursor = Math.max(0, cursor - 1);
        } else if (codePoint == '\t') {
            cursor = Math.min(MAX_CELLS, ((cursor / 8) + 1) * 8);
        } else if (codePoint >= 32 && codePoint != 127) {
            put(codePoint);
        }
    }

    private void escape(int command) {
        switch (command) {
            case '7' -> savedCursor = cursor;
            case '8' -> cursor = savedCursor;
            case 'D', 'E' -> commitAndClear();
            case 'c' -> reset();
            default -> { }
        }
    }

    private void csi(int command, String raw) {
        int[] values = parameters(raw);
        int first = value(values, 0, 1);
        switch (command) {
            case 'C' -> cursor = Math.min(MAX_CELLS, cursor + first);
            case 'D' -> cursor = Math.max(0, cursor - first);
            case 'E', 'F' -> commitAndClear();
            case 'G' -> cursor = boundedColumn(first - 1);
            case 'H', 'f' -> cursor = boundedColumn(value(values, 1, 1) - 1);
            case 'J' -> {
                int mode = value(values, 0, 0);
                if (mode == 2 || mode == 3) reset();
            }
            case 'K' -> eraseLine(value(values, 0, 0));
            case 's' -> savedCursor = cursor;
            case 'u' -> cursor = savedCursor;
            case '@' -> insertCells(first);
            case 'P' -> deleteCells(first);
            case 'X' -> eraseCells(first);
            default -> { }
        }
    }

    private void put(int codePoint) {
        if (cursor >= MAX_CELLS) return;
        while (cells.size() < cursor) cells.add(" ");
        String value = new String(Character.toChars(codePoint));
        if (cursor == cells.size()) cells.add(value);
        else cells.set(cursor, value);
        cursor++;
    }

    private void eraseLine(int mode) {
        if (mode == 2) {
            cells.clear();
            cursor = 0;
        } else if (mode == 1) {
            for (int index = 0; index < Math.min(cells.size(), cursor + 1); index++) cells.set(index, " ");
        } else {
            while (cells.size() > cursor) cells.removeLast();
        }
    }

    private void insertCells(int count) {
        int amount = Math.min(Math.max(0, count), MAX_CELLS - Math.min(cursor, MAX_CELLS));
        while (cells.size() < cursor) cells.add(" ");
        for (int index = 0; index < amount; index++) cells.add(Math.min(cursor, cells.size()), " ");
        while (cells.size() > MAX_CELLS) cells.removeLast();
    }

    private void deleteCells(int count) {
        for (int index = 0; index < count && cursor < cells.size(); index++) cells.remove(cursor);
    }

    private void eraseCells(int count) {
        for (int index = cursor; index < Math.min(cells.size(), cursor + count); index++) cells.set(index, " ");
    }

    private void commitAndClear() {
        String value = render().strip();
        if (!value.isEmpty()) lastNonEmptyLine = value;
        cells.clear();
        cursor = 0;
    }

    private String render() {
        StringBuilder value = new StringBuilder();
        cells.forEach(value::append);
        return value.toString().stripTrailing();
    }

    private void reset() {
        cells.clear();
        cursor = 0;
        savedCursor = 0;
        lastNonEmptyLine = null;
    }

    private static int boundedColumn(int value) {
        return Math.min(MAX_CELLS, Math.max(0, value));
    }

    private static int[] parameters(String raw) {
        String cleaned = raw.replaceFirst("^[?<>=!]", "");
        if (cleaned.isEmpty()) return new int[0];
        String[] parts = cleaned.split(";", -1);
        int[] values = new int[parts.length];
        for (int index = 0; index < parts.length; index++) {
            try { values[index] = parts[index].isEmpty() ? 0 : Integer.parseInt(parts[index]); }
            catch (NumberFormatException ignored) { values[index] = 0; }
        }
        return values;
    }

    private static int value(int[] values, int index, int fallback) {
        return index >= values.length || values[index] == 0 ? fallback : values[index];
    }
}
