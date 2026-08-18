package dev.termestra.team.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryRetryPolicyTest {
    private final DeliveryRetryPolicy policy = new DeliveryRetryPolicy();

    @Test void retriesOnlyProvenPreInputFailuresWithBoundedExponentialDelay() {
        assertEquals(Duration.ofSeconds(1), policy.afterFailure(1).delay());
        assertEquals(Duration.ofSeconds(2), policy.afterFailure(2).delay());
        assertEquals(Duration.ofSeconds(4), policy.afterFailure(3).delay());
        assertEquals(Duration.ofSeconds(8), policy.afterFailure(4).delay());
        assertFalse(policy.afterFailure(5).retry());
        assertNull(policy.afterFailure(5).delay());
    }

    @Test void attemptNumbersMustComeFromAPersistedClaim() {
        assertThrows(IllegalArgumentException.class, () -> policy.afterFailure(0));
    }
}
