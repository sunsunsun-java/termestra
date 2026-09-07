package dev.termestra.execution.adapter.out.terminal;

import dev.termestra.execution.application.port.out.PromptTerminal;
import dev.termestra.platform.terminal.HeadlessTerminalMirror;

/** Execution adapter sharing the terminal protocol implementation with browser restore. */
public final class VtPromptTerminal implements PromptTerminal {
    private final HeadlessTerminalMirror mirror = new HeadlessTerminalMirror(80, 24, 0);

    @Override public void write(String text) { mirror.write(text); }
    @Override public void resize(int columns, int rows) { mirror.resize(columns, rows); }
    @Override public View view() {
        var view = mirror.view();
        return new View(view.lines(), view.cursorRow(), view.cursorColumn(), view.lineRevisions());
    }
}
