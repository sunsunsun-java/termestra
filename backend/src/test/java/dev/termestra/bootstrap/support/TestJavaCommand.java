package dev.termestra.bootstrap.support;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds a command that launches a test fixture with the same JDK and classpath as Surefire. */
public record TestJavaCommand(String command, List<String> arguments) {
    public TestJavaCommand {
        arguments = List.copyOf(arguments);
    }

    public static TestJavaCommand fixture(Class<?> mainClass, String... fixtureArguments) {
        String executable = windows() ? "java.exe" : "java";
        String classPath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        List<String> arguments = new ArrayList<>(List.of("-cp", classPath, mainClass.getName()));
        arguments.addAll(List.of(fixtureArguments));
        return new TestJavaCommand(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(), arguments);
    }

    /**
     * Starts a Java fixture with the raw POSIX terminal mode required by bracketed-paste tests.
     * Windows ConPTY does not provide {@code stty}, so it launches the same Java fixture directly.
     */
    public static TestJavaCommand rawTerminalFixture(Class<?> mainClass, String... fixtureArguments) {
        TestJavaCommand fixture = fixture(mainClass, fixtureArguments);
        if (windows()) return fixture;
        List<String> arguments = new ArrayList<>(List.of(
                "-c", "stty raw -echo; exec \"$@\"", "termestra-test-fixture", fixture.command()));
        arguments.addAll(fixture.arguments());
        return new TestJavaCommand("/bin/sh", arguments);
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
