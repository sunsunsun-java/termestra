package dev.termestra.terminal.application.port.in;

@FunctionalInterface
public interface TerminalSubscription extends AutoCloseable {
    @Override void close();
}
