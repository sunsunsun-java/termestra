package dev.termestra.team.application.port.in;

import dev.termestra.team.application.exception.TeamBadRequest;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamInputLimitsTest {
    @Test void acceptsValuesExactlyAtTheDocumentedWriteBoundaries() {
        assertEquals("n".repeat(TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS),
                TeamInputLimits.memberName("n".repeat(TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS)));
        assertEquals(TeamInputLimits.MAX_MEMBER_DESCRIPTION_CHARACTERS,
                TeamInputLimits.memberDescription("d".repeat(
                        TeamInputLimits.MAX_MEMBER_DESCRIPTION_CHARACTERS)).length());
        assertEquals(TeamInputLimits.MAX_TASK_TEXT_CHARACTERS,
                TeamInputLimits.taskText("t".repeat(TeamInputLimits.MAX_TASK_TEXT_CHARACTERS)).length());
        assertEquals(TeamInputLimits.MAX_REPORT_TEXT_CHARACTERS,
                TeamInputLimits.reportText("r".repeat(TeamInputLimits.MAX_REPORT_TEXT_CHARACTERS)).length());
        assertEquals(TeamInputLimits.MAX_CANCEL_REASON_CHARACTERS,
                TeamInputLimits.cancelReason("c".repeat(TeamInputLimits.MAX_CANCEL_REASON_CHARACTERS)).length());
        assertEquals(TeamInputLimits.MAX_STATUS_CHARACTERS,
                TeamInputLimits.status("s".repeat(TeamInputLimits.MAX_STATUS_CHARACTERS)).length());
        assertEquals(TeamInputLimits.MAX_IDEMPOTENCY_KEY_CHARACTERS,
                TeamInputLimits.idempotencyKey("i".repeat(TeamInputLimits.MAX_IDEMPOTENCY_KEY_CHARACTERS)).length());
        assertEquals("65535", TeamInputLimits.runtimePort("65535"));

        List<String> artifacts = Collections.nCopies(
                TeamInputLimits.MAX_ARTIFACT_TOTAL_CHARACTERS /
                        TeamInputLimits.MAX_ARTIFACT_CHARACTERS,
                "a".repeat(TeamInputLimits.MAX_ARTIFACT_CHARACTERS));
        assertEquals(TeamInputLimits.MAX_ARTIFACT_TOTAL_CHARACTERS,
                TeamInputLimits.artifacts(artifacts).stream().mapToInt(String::length).sum());
        assertEquals(TeamInputLimits.MAX_ARTIFACTS,
                TeamInputLimits.artifacts(Collections.nCopies(TeamInputLimits.MAX_ARTIFACTS, "")).size());
    }

    @Test void rejectsEveryUnboundedTeamInputBeforePersistenceOrTerminalDelivery() {
        assertThrows(TeamBadRequest.class, () -> TeamInputLimits.memberName(
                "n".repeat(TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS + 1)));
        assertThrows(TeamBadRequest.class, () -> TeamInputLimits.memberDescription(
                "d".repeat(TeamInputLimits.MAX_MEMBER_DESCRIPTION_CHARACTERS + 1)));
        assertThrows(TeamBadRequest.class, () -> TeamInputLimits.taskText(
                "t".repeat(TeamInputLimits.MAX_TASK_TEXT_CHARACTERS + 1)));
        assertThrows(TeamBadRequest.class, () -> TeamInputLimits.reportText(
                "r".repeat(TeamInputLimits.MAX_REPORT_TEXT_CHARACTERS + 1)));
        assertThrows(TeamBadRequest.class, () -> new ApplyTeamScenarioCommand(
                "workspace", "scenario", "g".repeat(TeamInputLimits.MAX_TASK_TEXT_CHARACTERS + 1),
                "zh", "3000"));
        assertThrows(TeamBadRequest.class, () -> TeamInputLimits.cancelReason(
                "c".repeat(TeamInputLimits.MAX_CANCEL_REASON_CHARACTERS + 1)));
        assertThrows(TeamBadRequest.class, () -> TeamInputLimits.status(
                "s".repeat(TeamInputLimits.MAX_STATUS_CHARACTERS + 1)));
        assertThrows(TeamBadRequest.class, () -> TeamInputLimits.idempotencyKey(
                "i".repeat(TeamInputLimits.MAX_IDEMPOTENCY_KEY_CHARACTERS + 1)));
        assertThrows(TeamBadRequest.class, () -> TeamInputLimits.runtimePort("0"));
        assertThrows(TeamBadRequest.class, () -> TeamInputLimits.runtimePort("not-a-port"));
        assertThrows(TeamBadRequest.class, () -> TeamInputLimits.artifacts(
                Collections.nCopies(TeamInputLimits.MAX_ARTIFACTS + 1, "a")));
        assertThrows(TeamBadRequest.class, () -> TeamInputLimits.artifacts(List.of(
                "a".repeat(TeamInputLimits.MAX_ARTIFACT_CHARACTERS + 1))));
        assertThrows(TeamBadRequest.class, () -> TeamInputLimits.artifacts(Collections.nCopies(
                TeamInputLimits.MAX_ARTIFACT_TOTAL_CHARACTERS /
                        TeamInputLimits.MAX_ARTIFACT_CHARACTERS + 1,
                "a".repeat(TeamInputLimits.MAX_ARTIFACT_CHARACTERS))));
    }

    @Test void boundsOnlyLegacySummaryFieldsWithoutMutatingTheirSource() {
        String legacyName = "n".repeat(TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS + 20);
        String legacyPreset = "p".repeat(TeamInputLimits.MAX_PRESET_ID_CHARACTERS + 20);

        assertEquals(TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS,
                TeamInputLimits.boundedMemberName(legacyName).length());
        assertEquals(TeamInputLimits.MAX_PRESET_ID_CHARACTERS,
                TeamInputLimits.boundedPresetId(legacyPreset).length());
        assertEquals(TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS + 20, legacyName.length());
        assertEquals(TeamInputLimits.MAX_PRESET_ID_CHARACTERS + 20, legacyPreset.length());

        List<String> legacyArtifacts = Collections.nCopies(TeamInputLimits.MAX_ARTIFACTS + 20,
                "a".repeat(TeamInputLimits.MAX_ARTIFACT_CHARACTERS + 20));
        List<String> boundedArtifacts = TeamInputLimits.boundedArtifacts(legacyArtifacts);
        assertTrue(boundedArtifacts.size() <= TeamInputLimits.MAX_ARTIFACTS);
        assertTrue(boundedArtifacts.stream().allMatch(value ->
                value.length() <= TeamInputLimits.MAX_ARTIFACT_CHARACTERS));
        assertTrue(boundedArtifacts.stream().mapToInt(String::length).sum()
                <= TeamInputLimits.MAX_ARTIFACT_TOTAL_CHARACTERS);
        assertEquals(TeamInputLimits.MAX_ARTIFACTS + 20, legacyArtifacts.size());
    }

    @Test void legacyProjectionNeverSplitsASupplementaryCharacter() {
        String value="x".repeat(TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS-1)+"😀tail";

        String bounded=TeamInputLimits.boundedMemberName(value);

        assertEquals(TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS-1,bounded.length());
        assertTrue(Character.isLowSurrogate(value.charAt(TeamInputLimits.MAX_MEMBER_NAME_CHARACTERS)));
    }
}
