package dev.termestra.execution.adapter.out.system;

import dev.termestra.execution.application.port.out.ShellCommandResolver;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class SystemShellCommandResolver implements ShellCommandResolver {
    @Override public ShellCommand resolve(String startupCommand) {
        String shell=Objects.requireNonNullElse(System.getenv("SHELL"),"/bin/sh");
        String shellName=Path.of(shell).getFileName().toString().toLowerCase(Locale.ROOT);
        String option=shellName.contains("bash")||shellName.contains("zsh")||shellName.contains("ksh")
                ?"-lic":"-ic";
        return new ShellCommand(shell,List.of(option,startupCommand));
    }
}
