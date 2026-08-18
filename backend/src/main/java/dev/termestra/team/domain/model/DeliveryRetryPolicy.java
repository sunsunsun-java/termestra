package dev.termestra.team.domain.model;

import java.time.Duration;

/** Pure policy for failures proven to have happened before terminal input. */
public final class DeliveryRetryPolicy {
    public static final int MAX_AUTOMATIC_ATTEMPTS = 5;

    public RetryDecision afterFailure(int completedAttempts) {
        if (completedAttempts < 1) throw new IllegalArgumentException("completedAttempts must be positive");
        if (completedAttempts >= MAX_AUTOMATIC_ATTEMPTS) return RetryDecision.stop();
        return RetryDecision.retryAfter(Duration.ofSeconds(1L << (completedAttempts - 1)));
    }

    public record RetryDecision(boolean retry, Duration delay) {
        public RetryDecision {
            if (retry && (delay == null || delay.isNegative() || delay.isZero())) {
                throw new IllegalArgumentException("retry delay must be positive");
            }
            if (!retry && delay != null) throw new IllegalArgumentException("stopped retry has no delay");
        }
        static RetryDecision retryAfter(Duration delay) { return new RetryDecision(true, delay); }
        static RetryDecision stop() { return new RetryDecision(false, null); }
    }
}
