package dev.termestra.execution.application.port.out;

import java.util.List;

/** Resolves a user shell snippet without exposing operating-system details to application code. */
public interface ShellCommandResolver {
    ShellCommand resolve(String startupCommand);

    record ShellCommand(String command, List<String> arguments) { }
}
