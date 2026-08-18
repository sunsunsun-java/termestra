package dev.termestra.shared.concurrency;

/** Preserves interruption while translating lock acquisition into the runtime coordination domain. */
public final class RuntimeOperationInterruptedException extends RuntimeException {
    RuntimeOperationInterruptedException(String message, InterruptedException cause) {
        super(message, cause);
    }
}
