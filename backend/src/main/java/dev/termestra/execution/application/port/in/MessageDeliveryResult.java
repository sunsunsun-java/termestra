package dev.termestra.execution.application.port.in;

/**
 * Reports what is known about a write to an agent's interactive input.
 *
 * <p>{@code delivered} means the complete input was submitted. {@code inputAttempted}
 * records that delivery crossed the point where the worker might have observed bytes,
 * while {@code uncertain} means the caller cannot prove whether the complete input was
 * accepted. Keeping these facts separate prevents a durable dispatch from being deleted
 * after it may already have reached the worker.</p>
 */
public record MessageDeliveryResult(boolean delivered, boolean inputAttempted,
                                    boolean uncertain, String error) {
    /** Compatibility constructor for callers that only distinguish success and failure. */
    public MessageDeliveryResult(boolean delivered, String error) {
        this(delivered, delivered, false, error);
    }

    public static MessageDeliveryResult success() {
        return new MessageDeliveryResult(true, true, false, null);
    }

    /** A definite failure before any input could have reached the worker. */
    public static MessageDeliveryResult failed(String error) {
        return new MessageDeliveryResult(false, false, false, error);
    }

    /** A write was attempted, but complete submission cannot be proven. */
    public static MessageDeliveryResult uncertain(String error) {
        return new MessageDeliveryResult(false, true, true, error);
    }
}
