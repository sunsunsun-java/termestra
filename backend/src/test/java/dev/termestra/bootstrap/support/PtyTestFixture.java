package dev.termestra.bootstrap.support;

import java.io.IOException;

/** Minimal portable subprocess used by PTY integration tests. */
public final class PtyTestFixture {
    private PtyTestFixture() { }

    public static void main(String[] args) throws IOException, InterruptedException {
        String mode = args.length == 0 ? "echo" : args[0];
        switch (mode) {
            case "echo" -> echoInput();
            case "runtime-port" -> {
                System.out.println("port=" + System.getenv().getOrDefault("TERMESTRA_PORT", ""));
                System.out.flush();
                echoInput();
            }
            case "cursor-startup" -> {
                var ready = java.nio.file.Path.of(args[1]);
                if (!java.nio.file.Files.exists(ready)) {
                    System.out.println("Login required\nSign in");
                    System.out.flush();
                    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
                    while (!java.nio.file.Files.exists(ready)) {
                        if (System.nanoTime() >= deadline) throw new IOException("fixture readiness was not released");
                        Thread.sleep(10);
                    }
                }
                System.out.print("\u001b[2J\u001b[H  → Plan, search, build anything");
                System.out.flush();
                echoInput();
            }
            case "codex-startup" -> {
                System.out.print("\u001b[2J\u001b[H» \u001b[2mAsk Codex to do anything\u001b[0m\u001b[1;3H");
                System.out.flush();
                var input = new java.io.ByteArrayOutputStream();
                int value;
                while ((value = System.in.read()) >= 0 && value != '\r') {
                    if (input.size() >= 65_536) throw new IOException("fixture startup input exceeded its limit");
                    input.write(value);
                }
                String startup = input.toString(java.nio.charset.StandardCharsets.UTF_8);
                if (value != '\r' || !startup.startsWith("\u001b[200~<termestra-message kind=\"startup\">")
                        || !startup.endsWith("</termestra-message>\n\u001b[201~")) {
                    throw new IOException("fixture did not receive a complete startup submission");
                }
                System.out.print("\r\nstartup-submitted\r\n");
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
