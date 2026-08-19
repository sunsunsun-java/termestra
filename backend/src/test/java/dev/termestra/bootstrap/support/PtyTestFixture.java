package dev.termestra.bootstrap.support;

import java.io.IOException;

/** Minimal portable subprocess used by PTY integration tests. */
public final class PtyTestFixture {
    private PtyTestFixture() { }

    public static void main(String[] args) throws IOException {
        String mode = args.length == 0 ? "echo" : args[0];
        switch (mode) {
            case "echo" -> echoInput();
            case "runtime-port" -> {
                System.out.println("port=" + System.getenv().getOrDefault("TERMESTRA_PORT", ""));
                System.out.flush();
                echoInput();
            }
            case "exit" -> { }
            default -> throw new IllegalArgumentException("Unknown PTY test fixture mode: " + mode);
        }
    }

    private static void echoInput() throws IOException {
        byte[] buffer = new byte[8_192];
        int count;
        while ((count = System.in.read(buffer)) >= 0) {
            if (count == 0) continue;
            System.out.write(buffer, 0, count);
            System.out.flush();
        }
    }
}
