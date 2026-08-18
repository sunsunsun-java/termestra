package dev.termestra.team.application.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioMemberNameGeneratorTest {
    @Test void usesTheCompleteNameBankAndAddsAShortSuffixOnlyAfterExhaustion() {
        assertEquals(1_111, ScenarioMemberNameGenerator.poolSize());
        Set<String> used = new HashSet<>();
        for (int index = 0; index < ScenarioMemberNameGenerator.poolSize(); index++) {
            assertTrue(used.add(ScenarioMemberNameGenerator.next("coder", used)));
        }

        String exhaustedFallback = ScenarioMemberNameGenerator.next("coder", used);
        assertFalse(used.contains(exhaustedFallback));
        assertTrue(exhaustedFallback.matches(".+-[0-9a-z]{4}"));
    }
}
