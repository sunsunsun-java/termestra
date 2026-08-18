package dev.termestra.terminal.application.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Bounded, deterministic VT screen used only to create reconnect snapshots.
 *
 * <p>The mirror deliberately owns terminal state instead of retaining an unbounded raw transcript.
 * It supports the stateful sequences emitted by the supported interactive CLIs: primary/alternate
 * screens, deferred autowrap, SGR attributes, scrolling regions and cursor save/restore.</p>
 */
public final class HeadlessTerminalMirror {
    private static final int DEFAULT_SCROLLBACK = 1_000;
    private static final int MAX_COLUMNS = 400;
    private static final int MAX_ROWS = 150;
    private static final int MAX_SEQUENCE = 256;
    private static final int MAX_STYLE_SEQUENCE = 512;
    private static final int MAX_RETAINED_STYLES = 256;
    private static final Set<Integer> SNAPSHOT_DEC_MODES = Set.of(
            1, 3, 5, 6, 7, 9, 12, 25, 66, 67,
            1000, 1002, 1003, 1004, 1005, 1006, 1015,
            1047, 1048, 1049, 2004, 2026);
    private static final int MAX_CSI_PARAMETER = 10_000;
    /** Caps backing cell references even at hostile maximum dimensions. */
    private static final int MAX_RETAINED_CELLS = 100_000;
    /** Leaves room for the surrounding JSON control envelope inside the 1 MiB websocket frame. */
    static final int MAX_SNAPSHOT_TRANSPORT_BYTES = 900 * 1024;
    private static final int MAX_STYLED_SNAPSHOT_CHARACTERS = 128 * 1024;

    private final int requestedScrollback;
    private int columns;
    private int rows;
    private Screen primary;
    private Screen alternate;
    private Screen active;
    private ParserState parserState = ParserState.TEXT;
    private final StringBuilder sequence = new StringBuilder();
    private boolean sequenceOverflow;
    private final IncrementalUtf8Decoder utf8 = new IncrementalUtf8Decoder();

    private enum ParserState { TEXT, ESCAPE, CSI, OSC, OSC_ESCAPE }

    public HeadlessTerminalMirror() { this(80, 24); }
    public HeadlessTerminalMirror(int columns, int rows) { this(columns, rows, DEFAULT_SCROLLBACK); }

    HeadlessTerminalMirror(int columns, int rows, int scrollback) {
        requestedScrollback = Math.max(0, Math.min(DEFAULT_SCROLLBACK, scrollback));
        this.columns = bounded(columns, 1, MAX_COLUMNS);
        this.rows = bounded(rows, 1, MAX_ROWS);
        primary = new Screen(true);
        alternate = new Screen(false);
        active = primary;
    }

    public synchronized void resize(int nextColumns, int nextRows) {
        columns = bounded(nextColumns, 1, MAX_COLUMNS);
        rows = bounded(nextRows, 1, MAX_ROWS);
        primary.resize();
        alternate.resize();
    }

    public synchronized void write(byte[] bytes) { write(utf8.decode(bytes)); }

    public synchronized void write(String text) {
        text.codePoints().forEach(this::accept);
        primary.trimHistory();
    }

    /** ANSI checkpoint that reconstructs both buffers, modes and the final cursor on a fresh xterm. */
    public synchronized String snapshot() {
        String styled = checkpoint(true, true, MAX_STYLED_SNAPSHOT_CHARACTERS);
        if (styled != null && jsonStringBytes(styled) <= MAX_SNAPSHOT_TRANSPORT_BYTES) {
            return styled;
        }

        // A hostile stream can alternate a long SGR sequence for every cell. Replaying those
        // styles is cosmetic; allowing it to create a multi-megabyte websocket frame is not.
        String plainVisibleBuffers = checkpoint(false, false, Integer.MAX_VALUE);
        if (jsonStringBytes(plainVisibleBuffers) <= MAX_SNAPSHOT_TRANSPORT_BYTES) {
            return plainVisibleBuffers;
        }

        // This is only reachable if future dimension limits grow without updating the budget.
        // Preserve the active visible screen and cursor instead of returning a truncated ANSI
        // sequence that could leave xterm in a corrupted parser state.
        StringBuilder minimal = new StringBuilder("\033c");
        active.appendCheckpoint(minimal, false, false, Integer.MAX_VALUE);
        active.appendState(minimal);
        return jsonStringBytes(minimal) <= MAX_SNAPSHOT_TRANSPORT_BYTES ? minimal.toString() : "\033c";
    }

    private String checkpoint(boolean includeHistory, boolean includeStyles, int characterLimit) {
        StringBuilder checkpoint = new StringBuilder("\033c");
        if (!primary.appendCheckpoint(checkpoint, includeHistory, includeStyles, characterLimit)) {
            return null;
        }
        primary.appendState(checkpoint);
        if (checkpoint.length() > characterLimit) return null;
        if (active == alternate) {
            checkpoint.append("\033[?1049h");
            if (!alternate.appendCheckpoint(checkpoint, includeHistory, includeStyles, characterLimit)) {
                return null;
            }
            alternate.appendState(checkpoint);
        }
        return checkpoint.length() <= characterLimit ? checkpoint.toString() : null;
    }

    synchronized String screenText() { return active.render(); }

    public synchronized String lastPtyLine(int maximum) {
        List<Cell[]> all = active.allLines();
        for (int index = all.size() - 1; index >= 0; index--) {
            String value = plain(all.get(index)).strip();
            if (!value.isEmpty()) return value.substring(0, Math.min(Math.max(0, maximum), value.length()));
        }
        return null;
    }

    private void accept(int codePoint) {
        if (parserState == ParserState.OSC) {
            if (codePoint == 7) resetParser();
            else if (codePoint == 27) parserState = ParserState.OSC_ESCAPE;
            return;
        }
        if (parserState == ParserState.OSC_ESCAPE) {
            if (codePoint == '\\') resetParser();
            else parserState = ParserState.OSC;
            return;
        }
        if (parserState == ParserState.ESCAPE) {
            if (codePoint == '[') begin(ParserState.CSI);
            else if (codePoint == ']') begin(ParserState.OSC);
            else { escape(codePoint); resetParser(); }
            return;
        }
        if (parserState == ParserState.CSI) {
            if (codePoint >= 0x40 && codePoint <= 0x7e) {
                if (!sequenceOverflow) csi(codePoint, sequence.toString());
                resetParser();
            } else if (sequence.length() < MAX_SEQUENCE) sequence.appendCodePoint(codePoint);
            else sequenceOverflow = true;
            return;
        }
        if (codePoint == 27) { parserState = ParserState.ESCAPE; return; }
        if (codePoint == '\r') { active.carriageReturn(); return; }
        if (codePoint == '\n' || codePoint == 11 || codePoint == 12) { active.lineFeed(); return; }
        if (codePoint == '\b') { active.backspace(); return; }
        if (codePoint == '\t') { active.tab(); return; }
        if (codePoint < 32 || codePoint == 127) return;
        active.put(codePoint);
    }

    private void begin(ParserState next) {
        parserState = next;
        sequence.setLength(0);
        sequenceOverflow = false;
    }

    private void resetParser() {
        parserState = ParserState.TEXT;
        sequence.setLength(0);
        sequenceOverflow = false;
    }

    private void escape(int command) {
        switch (command) {
            case '7' -> active.saveCursor();
            case '8' -> active.restoreCursor();
            case 'D' -> active.lineFeed();
            case 'E' -> { active.lineFeed(); active.carriageReturn(); }
            case 'M' -> active.reverseIndex();
            case 'c' -> reset();
            default -> { }
        }
    }

    private void csi(int command, String raw) {
        boolean privateMode = raw.startsWith("?");
        int[] values = parameters(privateMode ? raw.substring(1) : raw);
        int first = value(values, 0, 1);
        if (privateMode && (command == 'h' || command == 'l')) {
            privateMode(values, command == 'h');
            return;
        }
        switch (command) {
            case 'A' -> active.moveVertical(-first);
            case 'B' -> active.moveVertical(first);
            case 'C' -> active.moveHorizontal(first);
            case 'D' -> active.moveHorizontal(-first);
            case 'E' -> { active.moveVertical(first); active.carriageReturn(); }
            case 'F' -> { active.moveVertical(-first); active.carriageReturn(); }
            case 'G' -> active.setColumn(first - 1);
            case 'd' -> active.setRow(first - 1);
            case 'H', 'f' -> active.position(value(values, 0, 1) - 1, value(values, 1, 1) - 1);
            case 'J' -> active.eraseDisplay(value(values, 0, 0));
            case 'K' -> active.eraseLine(value(values, 0, 0));
            case 'm' -> active.sgr(raw);
            case 'r' -> active.scrollRegion(value(values, 0, 1) - 1,
                    value(values, 1, rows) - 1);
            case 's' -> active.saveCursor();
            case 'u' -> active.restoreCursor();
            case '@' -> active.insertCells(first);
            case 'P' -> active.deleteCells(first);
            case 'X' -> active.eraseCells(first);
            case 'L' -> active.insertLines(first);
            case 'M' -> active.deleteLines(first);
            default -> { }
        }
    }

    private void privateMode(int[] modes, boolean enabled) {
        for (int mode : modes) {
            switch (mode) {
                case 6 -> { active.originMode = enabled; active.setMode(mode, enabled); }
                case 7 -> { active.autowrap = enabled; active.setMode(mode, enabled); }
                case 47, 1047, 1049 -> switchAlternate(enabled, mode == 1049);
                default -> active.setMode(mode, enabled);
            }
        }
    }

    private void switchAlternate(boolean enabled, boolean saveCursor) {
        if (enabled) {
            if (active == alternate) return;
            if (saveCursor) primary.saveCursor();
            alternate.clear();
            active = alternate;
        } else {
            if (active == primary) return;
            active = primary;
            if (saveCursor) primary.restoreCursor();
        }
    }

    private void reset() {
        primary.clear();
        alternate.clear();
        active = primary;
        resetParser();
    }

    private final class Screen {
        private final boolean retainsHistory;
        private final List<Cell[]> history = new ArrayList<>();
        private final List<Cell[]> screen = new ArrayList<>();
        private int cursorRow;
        private int cursorColumn;
        private int savedRow;
        private int savedColumn;
        private int scrollTop;
        private int scrollBottom;
        private boolean originMode;
        private boolean autowrap = true;
        private boolean wrapPending;
        private String style = "";
        private String savedStyle = "";
        private int pendingCodePoint;
        private String pendingStyle = "";
        private final java.util.TreeSet<Integer> decModes = new java.util.TreeSet<>();
        private final java.util.HashSet<String> retainedStyles = new java.util.HashSet<>();

        private Screen(boolean retainsHistory) {
            this.retainsHistory = retainsHistory;
            while (screen.size() < rows) screen.add(blank());
            scrollBottom = rows - 1;
        }

        private void resize() {
            for (int index = 0; index < history.size(); index++) history.set(index, resizeLine(history.get(index)));
            for (int index = 0; index < screen.size(); index++) screen.set(index, resizeLine(screen.get(index)));
            while (screen.size() < rows) screen.add(blank());
            while (screen.size() > rows) {
                Cell[] removed = screen.removeFirst();
                if (retainsHistory) history.add(removed);
            }
            cursorRow = bounded(cursorRow, 0, rows - 1);
            cursorColumn = bounded(cursorColumn, 0, columns - 1);
            savedRow = bounded(savedRow, 0, rows - 1);
            savedColumn = bounded(savedColumn, 0, columns - 1);
            scrollTop = 0;
            scrollBottom = rows - 1;
            wrapPending = false;
            trimHistory();
        }

        private void clear() {
            history.clear();
            screen.clear();
            while (screen.size() < rows) screen.add(blank());
            cursorRow = cursorColumn = savedRow = savedColumn = scrollTop = 0;
            scrollBottom = rows - 1;
            originMode = false;
            autowrap = true;
            wrapPending = false;
            style = "";
            savedStyle = "";
            pendingCodePoint = 0;
            pendingStyle = "";
            decModes.clear();
            retainedStyles.clear();
        }

        private void put(int codePoint) {
            if (wrapPending) {
                if (autowrap) { cursorColumn = 0; lineFeed(); }
                wrapPending = false;
            }
            int width = width(codePoint);
            if (width == 2 && cursorColumn == columns - 1 && autowrap) {
                cursorColumn = 0;
                lineFeed();
            }
            Cell[] line = screen.get(cursorRow);
            line[cursorColumn] = new Cell(new String(Character.toChars(codePoint)), retainedStyle(style));
            if (width == 2 && cursorColumn + 1 < columns) line[cursorColumn + 1] = Cell.CONTINUATION;
            if (cursorColumn + width >= columns) {
                cursorColumn = columns - 1;
                wrapPending = autowrap;
                pendingCodePoint = codePoint;
                pendingStyle = style;
            } else cursorColumn += width;
        }

        private void carriageReturn() { cursorColumn = 0; wrapPending = false; }
        private void backspace() { cursorColumn = Math.max(0, cursorColumn - 1); wrapPending = false; }
        private void tab() {
            cursorColumn = Math.min(columns - 1, ((cursorColumn / 8) + 1) * 8);
            wrapPending = false;
        }

        private void lineFeed() {
            wrapPending = false;
            if (cursorRow == scrollBottom) { scrollUp(); return; }
            if (cursorRow < rows - 1) cursorRow++;
        }

        private void reverseIndex() {
            wrapPending = false;
            if (cursorRow == scrollTop) {
                screen.add(scrollTop, blank());
                screen.remove(scrollBottom + 1);
            } else cursorRow = Math.max(0, cursorRow - 1);
        }

        private void scrollUp() {
            Cell[] removed = screen.remove(scrollTop);
            screen.add(scrollBottom, blank());
            if (retainsHistory && scrollTop == 0 && scrollBottom == rows - 1) {
                history.add(removed);
                trimHistory();
            }
        }

        private void moveVertical(int amount) {
            int minimum = originMode ? scrollTop : 0;
            int maximum = originMode ? scrollBottom : rows - 1;
            cursorRow = bounded(cursorRow + amount, minimum, maximum);
            wrapPending = false;
        }

        private void moveHorizontal(int amount) {
            cursorColumn = bounded(cursorColumn + amount, 0, columns - 1);
            wrapPending = false;
        }

        private void setRow(int row) {
            cursorRow = bounded((originMode ? scrollTop : 0) + row,
                    originMode ? scrollTop : 0, originMode ? scrollBottom : rows - 1);
            wrapPending = false;
        }

        private void setColumn(int column) {
            cursorColumn = bounded(column, 0, columns - 1);
            wrapPending = false;
        }

        private void position(int row, int column) { setRow(row); setColumn(column); }

        private void saveCursor() { savedRow = cursorRow; savedColumn = cursorColumn; savedStyle = style; }
        private void restoreCursor() {
            cursorRow = bounded(savedRow, 0, rows - 1);
            cursorColumn = bounded(savedColumn, 0, columns - 1);
            style = savedStyle;
            wrapPending = false;
        }

        private void eraseDisplay(int mode) {
            if (mode == 2 || mode == 3) {
                if (mode == 3) history.clear();
                for (Cell[] line : screen) Arrays.fill(line, null);
                return;
            }
            if (mode == 0) {
                eraseLine(0);
                for (int row = cursorRow + 1; row < rows; row++) Arrays.fill(screen.get(row), null);
            } else if (mode == 1) {
                eraseLine(1);
                for (int row = 0; row < cursorRow; row++) Arrays.fill(screen.get(row), null);
            }
        }

        private void eraseLine(int mode) {
            Cell[] line = screen.get(cursorRow);
            if (mode == 2) Arrays.fill(line, null);
            else if (mode == 1) Arrays.fill(line, 0, Math.min(columns, cursorColumn + 1), null);
            else Arrays.fill(line, Math.min(cursorColumn, columns), columns, null);
        }

        private void insertCells(int count) {
            Cell[] line = screen.get(cursorRow);
            int amount = Math.min(count, columns - cursorColumn);
            System.arraycopy(line, cursorColumn, line, cursorColumn + amount,
                    columns - cursorColumn - amount);
            Arrays.fill(line, cursorColumn, cursorColumn + amount, null);
        }

        private void deleteCells(int count) {
            Cell[] line = screen.get(cursorRow);
            int amount = Math.min(count, columns - cursorColumn);
            System.arraycopy(line, cursorColumn + amount, line, cursorColumn,
                    columns - cursorColumn - amount);
            Arrays.fill(line, columns - amount, columns, null);
        }

        private void eraseCells(int count) {
            Arrays.fill(screen.get(cursorRow), cursorColumn,
                    Math.min(columns, cursorColumn + count), null);
        }

        private void insertLines(int count) {
            if (cursorRow < scrollTop || cursorRow > scrollBottom) return;
            int amount = Math.min(count, scrollBottom - cursorRow + 1);
            for (int index = 0; index < amount; index++) {
                screen.add(cursorRow, blank());
                screen.remove(scrollBottom + 1);
            }
        }

        private void deleteLines(int count) {
            if (cursorRow < scrollTop || cursorRow > scrollBottom) return;
            int amount = Math.min(count, scrollBottom - cursorRow + 1);
            for (int index = 0; index < amount; index++) {
                screen.remove(cursorRow);
                screen.add(scrollBottom, blank());
            }
        }

        private void scrollRegion(int top, int bottom) {
            if (top < 0 || bottom >= rows || top >= bottom) {
                scrollTop = 0;
                scrollBottom = rows - 1;
            } else {
                scrollTop = top;
                scrollBottom = bottom;
            }
            cursorRow = originMode ? scrollTop : 0;
            cursorColumn = 0;
            wrapPending = false;
        }

        private void sgr(String raw) {
            String normalized = raw.isBlank() ? "0" : raw;
            String[] parts = normalized.split(";", -1);
            int lastReset = -1;
            for (int index = 0; index < parts.length; index++) {
                if (parts[index].isBlank() || "0".equals(parts[index])) lastReset = index;
            }
            if (lastReset >= 0) {
                style = "";
                if (lastReset == parts.length - 1) return;
                normalized = String.join(";", Arrays.copyOfRange(parts, lastReset + 1, parts.length));
            }
            String next = style + "\033[" + normalized + "m";
            style = next.length() <= MAX_STYLE_SEQUENCE ? next : "\033[" + normalized + "m";
        }

        private void setMode(int mode, boolean enabled) {
            // Only modes with meaningful xterm reconnect semantics are retained.
            // A hostile PTY must not grow this set by streaming arbitrary CSI ? n h values.
            if (!SNAPSHOT_DEC_MODES.contains(mode)) return;
            if (enabled) decModes.add(mode); else decModes.remove(mode);
        }

        private void appendState(StringBuilder target) {
            target.append("\033[").append(scrollTop + 1).append(';').append(scrollBottom + 1).append('r');
            if (!decModes.isEmpty()) {
                target.append("\033[?");
                boolean first = true;
                for (int mode : decModes) {
                    if (!first) target.append(';');
                    target.append(mode);
                    first = false;
                }
                target.append('h');
            }
            if (!autowrap) target.append("\033[?7l");
            appendStyle(target,savedStyle);
            appendCursor(target,savedRow,savedColumn);
            target.append("\0337");
            if(wrapPending&&pendingCodePoint!=0){
                int pendingWidth=width(pendingCodePoint);
                appendStyle(target,pendingStyle);
                appendCursor(target,cursorRow,Math.max(0,columns-pendingWidth));
                target.appendCodePoint(pendingCodePoint);
            }else{
                appendStyle(target,style);
                appendCursor(target,cursorRow,cursorColumn);
            }
        }

        private void appendCursor(StringBuilder target,int row,int column){
            int checkpointRow=originMode?row-scrollTop+1:row+1;
            target.append("\033[").append(Math.max(1,checkpointRow)).append(';')
                    .append(column+1).append('H');
        }

        private void appendStyle(StringBuilder target,String value){
            target.append("\033[0m");
            if(!value.isEmpty())target.append(value);
        }

        private String render() {
            List<Cell[]> all = allLines();
            int end = all.size();
            while (end > 0 && plain(all.get(end - 1)).isEmpty()) end--;
            StringBuilder result = new StringBuilder();
            for (int row = 0; row < end; row++) {
                if (row > 0) result.append("\r\n");
                renderLine(all.get(row), result);
            }
            return result.toString();
        }

        /**
         * A checkpoint must include every visible row, including trailing blank rows. Omitting
         * them moves old scrollback rows into the reconstructed viewport and makes subsequent
         * cursor-addressed output overwrite the wrong content.
         */
        private boolean appendCheckpoint(StringBuilder result, boolean includeHistory,
                                         boolean includeStyles, int characterLimit) {
            List<Cell[]> lines = includeHistory ? allLines() : screen;
            for (int row = 0; row < lines.size(); row++) {
                if (row > 0) result.append("\r\n");
                renderLine(lines.get(row), result, includeStyles);
                if (result.length() > characterLimit) return false;
            }
            return true;
        }

        private List<Cell[]> allLines() {
            List<Cell[]> all = new ArrayList<>(history.size() + screen.size());
            if (retainsHistory) all.addAll(history);
            all.addAll(screen);
            return all;
        }

        private void trimHistory() {
            int maximumRows = Math.max(0, Math.min(requestedScrollback,
                    MAX_RETAINED_CELLS / columns - rows));
            if (history.size() > maximumRows) history.subList(0, history.size() - maximumRows).clear();
        }

        private String retainedStyle(String candidate) {
            if (candidate.isEmpty() || retainedStyles.contains(candidate)) return candidate;
            if (retainedStyles.size() >= MAX_RETAINED_STYLES) return "";
            retainedStyles.add(candidate);
            return candidate;
        }
    }

    private record Cell(String text, String style) {
        private static final Cell CONTINUATION = new Cell("", "");
    }

    private Cell[] blank() { return new Cell[columns]; }

    private Cell[] resizeLine(Cell[] source) {
        Cell[] target = new Cell[columns];
        System.arraycopy(source, 0, target, 0, Math.min(source.length, columns));
        return target;
    }

    private static String plain(Cell[] line) {
        StringBuilder value = new StringBuilder();
        for (Cell cell : line) if (cell != null && !cell.text().isEmpty()) value.append(cell.text());
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') end--;
        return value.substring(0, end);
    }

    private static void renderLine(Cell[] line, StringBuilder target) {
        renderLine(line, target, true);
    }

    private static void renderLine(Cell[] line, StringBuilder target, boolean includeStyles) {
        int end = line.length;
        while (end > 0) {
            Cell cell = line[end - 1];
            if (cell != null && !cell.text().isEmpty() && !" ".equals(cell.text())) break;
            end--;
        }
        String activeStyle = "";
        for (int index = 0; index < end; index++) {
            Cell cell = line[index];
            String text = cell == null ? " " : cell.text();
            if (text.isEmpty()) continue;
            String style = !includeStyles || cell == null ? "" : cell.style();
            if (!style.equals(activeStyle)) {
                if (!activeStyle.isEmpty()) target.append("\033[0m");
                if (!style.isEmpty()) target.append(style);
                activeStyle = style;
            }
            target.append(text);
        }
        if (!activeStyle.isEmpty()) target.append("\033[0m");
    }

    private static int jsonStringBytes(CharSequence value) {
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\' || character == '\b'
                    || character == '\f' || character == '\n'
                    || character == '\r' || character == '\t') bytes += 2;
            else if (character <= 0x1f) bytes += 6;
            else if (Character.isHighSurrogate(character) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else if (Character.isSurrogate(character)) bytes += 6;
            else if (character <= 0x7f) bytes++;
            else if (character <= 0x7ff) bytes += 2;
            else bytes += 3;
            if (bytes > MAX_SNAPSHOT_TRANSPORT_BYTES) return MAX_SNAPSHOT_TRANSPORT_BYTES + 1;
        }
        return (int) bytes;
    }

    private static int[] parameters(String raw) {
        if (raw.isEmpty()) return new int[0];
        String[] parts = raw.split(";", -1);
        int[] values = new int[parts.length];
        for (int index = 0; index < parts.length; index++) {
            try { values[index] = parts[index].isEmpty() ? 0 : Integer.parseInt(parts[index]); }
            catch (NumberFormatException invalid) { values[index] = 0; }
        }
        return values;
    }

    private static int value(int[] values, int index, int fallback) {
        int result = index >= values.length || values[index] == 0 ? fallback : values[index];
        return bounded(result, 0, MAX_CSI_PARAMETER);
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int width(int codePoint) {
        return codePoint >= 0x1100 && (codePoint <= 0x115f || codePoint == 0x2329 || codePoint == 0x232a
                || (codePoint >= 0x2e80 && codePoint <= 0xa4cf)
                || (codePoint >= 0xac00 && codePoint <= 0xd7a3)
                || (codePoint >= 0xf900 && codePoint <= 0xfaff)
                || (codePoint >= 0xfe10 && codePoint <= 0xfe6f)
                || (codePoint >= 0xff00 && codePoint <= 0xff60)
                || (codePoint >= 0xffe0 && codePoint <= 0xffe6)
                || (codePoint >= 0x1f300 && codePoint <= 0x1faff)) ? 2 : 1;
    }
}
