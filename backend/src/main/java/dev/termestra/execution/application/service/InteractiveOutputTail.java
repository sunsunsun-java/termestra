package dev.termestra.execution.application.service;

import dev.termestra.execution.application.port.out.PromptTerminal;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Bounded output projection used only for interactive prompt and paste acknowledgement detection.
 * The retained terminal transcript remains owned by the run detail model.
 */
final class InteractiveOutputTail {
    static final int MAX_CHARS = 8_192;

    private static final Pattern PI_IDENTITY = Pattern.compile("(?is).*\\bpi\\s+v\\d+(?:\\.\\d+){1,3}.*");
    private final String command;
    private final PromptTerminal terminal;
    private boolean piIdentity;
    private Readiness readiness = new Readiness(State.INITIALIZING, null, 0, false);
    private String promptFingerprint;
    private String invalidatedFingerprint;

    InteractiveOutputTail(String command, PromptTerminal terminal) {
        this.command = InteractiveInputSubmitter.commandName(command);
        this.terminal = Objects.requireNonNull(terminal, "terminal");
    }

    synchronized void invalidateForUserInput() {
        invalidatedFingerprint = fingerprint(terminal.view());
        readiness = new Readiness(State.INITIALIZING, null, position, false);
        promptFingerprint = null;
    }

    synchronized void resize(int columns, int rows) {
        terminal.resize(columns, rows);
        observe();
    }

    private void observe() {
        PromptTerminal.View view = terminal.view();
        String screen = String.join("\n", view.lines());
        if ("pi".equals(command) && PI_IDENTITY.matcher(screen).matches()) piIdentity = true;
        boolean composerReady = InteractiveInputSubmitter.screenReady(terminal, view, command, piIdentity);
        String waiting = composerReady ? null : InteractiveInputSubmitter.waitingReason(screen);
        State state = composerReady ? State.READY : waiting != null ? State.WAITING_FOR_USER : State.INITIALIZING;
        String fingerprint = fingerprint(view);
        if (invalidatedFingerprint != null) {
            if (!invalidatedFingerprint.equals(fingerprint)) invalidatedFingerprint = null;
            else if (state == State.READY) state = State.INITIALIZING;
        }
        long readyPosition = state == State.READY && readiness.state() == State.READY
                && Objects.equals(fingerprint, promptFingerprint) ? readiness.position() : position;
        readiness = new Readiness(state, waiting, readyPosition, !"pi".equals(command)
                && InteractiveInputSubmitter.currentBusyIndicator(terminal, view, command));
        promptFingerprint = state == State.READY ? fingerprint : null;
    }

    private String fingerprint(PromptTerminal.View view) {
        // Cursor hides its cursor on the status row; only its actual composer paint counts.
        boolean cursor = "cursor-agent".equals(command);
        int row = cursor ? InteractiveInputSubmitter.cursorComposerRow(view) : view.cursorRow();
        if (row < 0 || row >= view.lines().size()) return "no-composer";
        // Identical prompt repaint is fresh evidence, even when clear + redraw arrive in one PTY
        // callback. Title/status output cannot release an invalidated or previously accepted prompt.
        return row + ":" + view.lineRevisions().get(row)
                + ":" + view.lines().get(row);
    }

    private final char[] tail = new char[MAX_CHARS];
    private int start;
    private int size;
    private long position;

    synchronized void append(String value) {
        if (value == null || value.isEmpty()) return;
        // Observe while consuming bounded pieces so a startup identity cannot be lost inside one
        // large PTY callback that also scrolls it off screen. Only identity, never old readiness, latches.
        for (int offset = 0; offset < value.length(); offset += 1024) {
            String chunk = value.substring(offset, Math.min(offset + 1024, value.length()));
            position += chunk.length();
            terminal.write(chunk);
            observe();
        }
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
        return new Snapshot(position, new String(copy), readiness);
    }

    enum State { INITIALIZING, WAITING_FOR_USER, READY }
    record Readiness(State state, String reason, long position, boolean busy) { }

    record Snapshot(long position, String tail, Readiness readiness) {
        String appendedSince(long baseline) {
            long tailStart = position - tail.length();
            if (baseline <= tailStart) return tail;
            if (baseline >= position) return "";
            return tail.substring((int) (baseline - tailStart));
        }
    }
}
