package dev.termestra.team.application.port.in;

import dev.termestra.team.application.exception.TeamBadRequest;

import java.util.ArrayList;
import java.util.List;

/** Limits user-controlled team data before it is persisted or delivered to a terminal. */
public final class TeamInputLimits {
    public static final int MAX_MEMBER_ID_CHARACTERS = 256;
    public static final int MAX_MEMBER_NAME_CHARACTERS = 128;
    public static final int MAX_MEMBER_DESCRIPTION_CHARACTERS = 4_096;
    public static final int MAX_ROLE_CHARACTERS = 64;
    public static final int MAX_TASK_TEXT_CHARACTERS = 65_536;
    public static final int MAX_REPORT_TEXT_CHARACTERS = 65_536;
    public static final int MAX_CANCEL_REASON_CHARACTERS = 8_192;
    public static final int MAX_STATUS_CHARACTERS = 64;
    public static final int MAX_ARTIFACTS = 64;
    public static final int MAX_ARTIFACT_CHARACTERS = 1_024;
    public static final int MAX_ARTIFACT_TOTAL_CHARACTERS = 32_768;
    public static final int MAX_PRESET_ID_CHARACTERS = 256;
    public static final int MAX_IDEMPOTENCY_KEY_CHARACTERS = 128;

    private TeamInputLimits() { }

    public static String memberName(String value) {
        String name = required(value, "name", MAX_MEMBER_NAME_CHARACTERS);
        return name.trim();
    }

    public static String memberDescription(String value) {
        return optional(value, "description", MAX_MEMBER_DESCRIPTION_CHARACTERS);
    }

    public static String taskText(String value) {
        return required(value, "text", MAX_TASK_TEXT_CHARACTERS);
    }

    public static String idempotencyKey(String value) {
        return required(value, "idempotency_key", MAX_IDEMPOTENCY_KEY_CHARACTERS).trim();
    }

    public static String runtimePort(String value) {
        String port = required(value, "runtime_port", 5).trim();
        try {
            int parsed = Integer.parseInt(port);
            if (parsed < 1 || parsed > 65_535) throw new NumberFormatException();
            return Integer.toString(parsed);
        } catch (NumberFormatException invalid) {
            throw new TeamBadRequest("runtime_port must be between 1 and 65535");
        }
    }

    public static String reportText(String value) {
        return required(value, "result", MAX_REPORT_TEXT_CHARACTERS);
    }

    public static String goal(String value) {
        return required(value, "goal", MAX_TASK_TEXT_CHARACTERS);
    }

    public static String cancelReason(String value) {
        return required(value, "reason", MAX_CANCEL_REASON_CHARACTERS);
    }

    public static String status(String value) {
        return optional(value, "status", MAX_STATUS_CHARACTERS);
    }

    public static List<String> artifacts(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > MAX_ARTIFACTS) {
            throw new TeamBadRequest("artifacts exceeds " + MAX_ARTIFACTS + " entries");
        }
        List<String> result = new ArrayList<>(values.size());
        long total = 0;
        for (String value : values) {
            if (value == null) continue;
            if (value.length() > MAX_ARTIFACT_CHARACTERS) {
                throw new TeamBadRequest(
                        "artifact exceeds " + MAX_ARTIFACT_CHARACTERS + " characters");
            }
            total += value.length();
            if (total > MAX_ARTIFACT_TOTAL_CHARACTERS) {
                throw new TeamBadRequest(
                        "artifacts exceed " + MAX_ARTIFACT_TOTAL_CHARACTERS + " total characters");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    public static String boundedMemberName(String value) {
        return bounded(value, MAX_MEMBER_NAME_CHARACTERS);
    }

    public static String boundedMemberDescription(String value) {
        return bounded(value, MAX_MEMBER_DESCRIPTION_CHARACTERS);
    }

    public static String boundedPresetId(String value) {
        return bounded(value, MAX_PRESET_ID_CHARACTERS);
    }

    public static List<String> boundedArtifacts(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> result = new ArrayList<>(Math.min(values.size(), MAX_ARTIFACTS));
        int remaining = MAX_ARTIFACT_TOTAL_CHARACTERS;
        for (String value : values) {
            if (result.size() == MAX_ARTIFACTS || remaining == 0) break;
            String safe = value == null ? "" : value;
            int length = Math.min(Math.min(safe.length(), MAX_ARTIFACT_CHARACTERS), remaining);
            String bounded = prefix(safe, length);
            result.add(bounded);
            remaining -= bounded.length();
        }
        return List.copyOf(result);
    }

    private static String required(String value, String field, int maximum) {
        if (value == null || value.trim().isEmpty()) throw new TeamBadRequest("Missing " + field);
        return optional(value, field, maximum);
    }

    private static String optional(String value, String field, int maximum) {
        if (value != null && value.length() > maximum) {
            throw new TeamBadRequest(field + " exceeds " + maximum + " characters");
        }
        return value;
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value;
        return prefix(value, maximum);
    }

    private static String prefix(String value, int maximum) {
        int end = Math.min(value.length(), maximum);
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end);
    }
}
