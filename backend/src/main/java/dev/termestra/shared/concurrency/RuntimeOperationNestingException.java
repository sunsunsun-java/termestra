package dev.termestra.shared.concurrency;

/** Indicates an unsupported shared-to-exclusive workspace lock upgrade by one thread. */
public final class RuntimeOperationNestingException extends IllegalStateException {
    RuntimeOperationNestingException(String message) {
        super(message);
    }
}
