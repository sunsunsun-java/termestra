package dev.termestra.team.application.port.out;

/** Delivery evidence carried across the team/execution bounded-context boundary. */
public record DeliveryResult(boolean forwarded, boolean inputAttempted,
                             boolean uncertain, String error) {
    /** Compatibility constructor for adapters that only distinguish success and failure. */
    public DeliveryResult(boolean forwarded, String error) {
        this(forwarded, forwarded, false, error);
    }

    /** A definite failure before any input could have reached the worker. */
    public static DeliveryResult unavailable(String error) {
        return new DeliveryResult(false, false, false, error);
    }

    /** A write was attempted, but complete submission cannot be proven. */
    public static DeliveryResult uncertain(String error) {
        return new DeliveryResult(false, true, true, error);
    }
}
