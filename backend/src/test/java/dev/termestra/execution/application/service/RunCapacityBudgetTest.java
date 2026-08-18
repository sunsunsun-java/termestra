package dev.termestra.execution.application.service;

import dev.termestra.execution.application.exception.ExecutionConflict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunCapacityBudgetTest {
    @Test void enforcesBothLimitsAndReleasesEachLeaseExactlyOnce() {
        RunCapacityBudget budget = new RunCapacityBudget(2, 1);
        RunCapacityBudget.Lease alpha = budget.reserve("alpha");

        assertThrows(ExecutionConflict.class, () -> budget.reserve("alpha"));
        RunCapacityBudget.Lease beta = budget.reserve("beta");
        assertThrows(ExecutionConflict.class, () -> budget.reserve("gamma"));

        alpha.close();
        alpha.close();
        assertDoesNotThrow(() -> budget.reserve("alpha").close());
        beta.close();
    }

    @Test void rejectsInvalidLimitConfigurations() {
        assertThrows(IllegalArgumentException.class, () -> new RunCapacityBudget(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RunCapacityBudget(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new RunCapacityBudget(1, 2));
    }
}
