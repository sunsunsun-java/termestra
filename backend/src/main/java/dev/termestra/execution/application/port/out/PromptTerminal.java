package dev.termestra.execution.application.port.out;

import java.util.List;

/** Bounded visible terminal projection used to observe interactive input readiness. */
public interface PromptTerminal {
    void write(String text);
    void resize(int columns, int rows);
    View view();
    Style styleAt(int row, int column);

    record Style(boolean dim, boolean italic, boolean inverse) { }

    /** Zero-based cursor coordinates; each visible line has a revision advanced on repaint. */
    record View(List<String> lines, int cursorRow, int cursorColumn, List<Long> lineRevisions) {
        public View { lines = List.copyOf(lines); lineRevisions = List.copyOf(lineRevisions); }
    }
}
