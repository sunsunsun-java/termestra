package dev.termestra.team.application.port.out;

/** Delivery evidence carried across the team/execution bounded-context boundary. */
public record DeliveryResult(boolean forwarded, boolean inputAttempted,
                             boolean uncertain, boolean deferred, String error) {
    public DeliveryResult {
        if (deferred && (forwarded || inputAttempted || uncertain)) {
            throw new IllegalArgumentException("Deferred delivery cannot have attempted input");
        }
    }

    public DeliveryResult(boolean forwarded, boolean inputAttempted,
                          boolean uncertain, String error) {
        this(forwarded, inputAttempted, uncertain, false, error);
    }
    /** Compatibility constructor for adapters that only distinguish success and failure. */
    public DeliveryResult(boolean forwarded, String error) {
        this(forwarded, forwarded, false, error);
    }

    /** A definite failure before any input could have reached the worker. */
    public static DeliveryResult unavailable(String error) {
        return new DeliveryResult(false, false, false, error);
    }

    /** Runtime startup is still in progress; keep the queued delivery without consuming an attempt. */
    public static DeliveryResult deferred(String reason) {
        return new DeliveryResult(false, false, false, true, reason);
    }

    /** A write was attempted, but complete submission cannot be proven. */
    public static DeliveryResult uncertain(String error) {
        return new DeliveryResult(false, true, true, error);
    }
}
