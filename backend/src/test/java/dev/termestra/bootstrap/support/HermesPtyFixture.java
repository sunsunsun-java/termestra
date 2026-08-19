package dev.termestra.bootstrap.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Small PTY fixture that models the Hermes bracketed-paste contract without
 * depending on a locally installed AI CLI or network access.
 */
public final class HermesPtyFixture {
    private static final byte[] PASTE_START = "\u001b[200~".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PASTE_END = "\u001b[201~".getBytes(StandardCharsets.UTF_8);

    private HermesPtyFixture() { }

    public static void main(String[] args) throws IOException {
        InputStream input = System.in;
        prompt();
        int pasteNumber = 0;
        while (await(input, PASTE_START)) {
            String text = new String(readUntil(input, PASTE_END), StandardCharsets.UTF_8);
            pasteNumber++;
            write("\r\n[Pasted text #" + pasteNumber + " +1 lines]");
            if (!awaitEnter(input)) return;
            if (text.contains("<termestra-message kind=\"startup\">")) {
                write("\r\nFIXTURE_RECEIVED_STARTUP\r\n");
            } else if (text.contains("HERMES_DELIVERY_TOKEN")) {
                write("\r\nFIXTURE_RECEIVED_TASK\r\n");
            } else {
                write("\r\nFIXTURE_RECEIVED_OTHER\r\n");
            }
            prompt();
        }
    }

    private static void prompt() throws IOException {
        write("Welcome to Hermes Agent! Type your message or /help for commands.\r\n"
                + "❯\r\n────────────────────────────────\r\n");
    }

    private static void write(String text) throws IOException {
        System.out.write(text.getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }

    private static boolean await(InputStream input, byte[] marker) throws IOException {
        int matched = 0;
        int value;
        while ((value = input.read()) >= 0) {
            if ((byte) value == marker[matched]) {
                matched++;
                if (matched == marker.length) return true;
            } else {
                matched = (byte) value == marker[0] ? 1 : 0;
            }
        }
        return false;
    }

    private static byte[] readUntil(InputStream input, byte[] marker) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matched = 0;
        int value;
        while ((value = input.read()) >= 0) {
            byte current = (byte) value;
            if (current == marker[matched]) {
                matched++;
                if (matched == marker.length) return output.toByteArray();
                continue;
            }
            if (matched > 0) {
                output.write(marker, 0, matched);
                matched = 0;
                if (current == marker[0]) {
                    matched = 1;
                    continue;
                }
            }
            output.write(current);
        }
        throw new IOException("PTY input ended inside bracketed paste");
    }

    private static boolean awaitEnter(InputStream input) throws IOException {
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\r' || value == '\n') return true;
        }
        return false;
    }
}
