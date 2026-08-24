import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import java.nio.charset.StandardCharsets;

public final class PtySmokeProbe {
    private static final String EXPECTED_OUTPUT = "PTY_SMOKE_OK";

    public static void main(String[] args) throws Exception {
        PtyProcess process = new PtyProcessBuilder(
                new String[] {"/bin/sh", "-c", "printf " + EXPECTED_OUTPUT})
                .setConsole(false)
                .setRedirectErrorStream(true)
                .setInitialColumns(80)
                .setInitialRows(24)
                .start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0 || !EXPECTED_OUTPUT.equals(output)) {
                throw new IllegalStateException(
                        "Packaged PTY probe failed: exit=" + exitCode + ", output=" + output);
            }
            System.out.print(EXPECTED_OUTPUT);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
