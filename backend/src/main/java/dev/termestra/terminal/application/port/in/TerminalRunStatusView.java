package dev.termestra.terminal.application.port.in;

public record TerminalRunStatusView(String status, Integer exitCode) {
    public boolean active() { return "starting".equals(status) || "running".equals(status); }
}
