package dev.termestra.execution.application.port.in;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounds data copied into process arguments, environments, persistence and terminal input. */
public final class ExecutionInputLimits {
    public static final int MAX_COMMAND_CHARACTERS = 4_096;
    public static final int MAX_ARGUMENTS = 128;
    // Java classpaths and shell launch snippets can legitimately exceed 8 KiB.
    // Keep the aggregate envelope bounded while allowing one larger argument.
    public static final int MAX_ARGUMENT_CHARACTERS = 32_768;
    public static final int MAX_ARGUMENT_TOTAL_CHARACTERS = 65_536;
    public static final int MAX_ENVIRONMENT_ENTRIES = 128;
    public static final int MAX_ENVIRONMENT_KEY_CHARACTERS = 256;
    public static final int MAX_ENVIRONMENT_VALUE_CHARACTERS = 16_384;
    public static final int MAX_ENVIRONMENT_TOTAL_CHARACTERS = 262_144;
    public static final int MAX_PRESET_ID_CHARACTERS = 256;
    public static final int MAX_CAPTURE_JSON_CHARACTERS = 65_536;
    public static final int MAX_SESSION_ID_CHARACTERS = 4_096;
    public static final int MAX_USER_INPUT_CHARACTERS = 65_536;
    public static final int MAX_MODEL_ID_CHARACTERS = 128;

    private ExecutionInputLimits() { }

    public static String command(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("command must not be blank");
        return bounded(value.trim(), "command", MAX_COMMAND_CHARACTERS);
    }

    public static String optionalCommand(String value, String field) {
        return value == null ? null : bounded(value, field, MAX_COMMAND_CHARACTERS);
    }

    public static String optionalPresetId(String value) {
        return value == null ? null : bounded(value, "command_preset_id", MAX_PRESET_ID_CHARACTERS);
    }

    public static String optionalModelId(String value) {
        if(value==null)return null;
        String normalized=value.trim();
        if(normalized.isEmpty())return null;
        bounded(normalized,"model_id",MAX_MODEL_ID_CHARACTERS);
        for(int index=0;index<normalized.length();index++){
            char character=normalized.charAt(index);
            if(Character.isISOControl(character)||Character.isWhitespace(character)){
                throw new IllegalArgumentException("model_id must not contain whitespace or control characters");
            }
        }
        return normalized;
    }

    public static String optionalCaptureJson(String value) {
        return value == null ? null : bounded(value, "session_id_capture_json", MAX_CAPTURE_JSON_CHARACTERS);
    }

    public static String sessionId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("session_id must not be blank");
        return bounded(value, "session_id", MAX_SESSION_ID_CHARACTERS);
    }

    public static String userInput(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("text is required");
        return bounded(value, "text", MAX_USER_INPUT_CHARACTERS);
    }

    public static List<String> arguments(List<String> values) {
        List<String> source = values == null ? List.of() : values;
        if (source.size() > MAX_ARGUMENTS) {
            throw new IllegalArgumentException("args exceeds " + MAX_ARGUMENTS + " entries");
        }
        long total = 0;
        for (String value : source) {
            if (value == null) throw new IllegalArgumentException("args must not contain null");
            bounded(value, "argument", MAX_ARGUMENT_CHARACTERS);
            total += value.length();
            if (total > MAX_ARGUMENT_TOTAL_CHARACTERS) {
                throw new IllegalArgumentException(
                        "args exceeds " + MAX_ARGUMENT_TOTAL_CHARACTERS + " total characters");
            }
        }
        return List.copyOf(source);
    }

    public static Map<String, String> environment(Map<String, String> values) {
        Map<String, String> source = values == null ? Map.of() : values;
        if (source.size() > MAX_ENVIRONMENT_ENTRIES) {
            throw new IllegalArgumentException(
                    "env exceeds " + MAX_ENVIRONMENT_ENTRIES + " entries");
        }
        long total = 0;
        Map<String, String> result = new LinkedHashMap<>(source.size());
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank()) throw new IllegalArgumentException("env key must not be blank");
            if (value == null) throw new IllegalArgumentException("env value must not be null");
            bounded(key, "env key", MAX_ENVIRONMENT_KEY_CHARACTERS);
            bounded(value, "env value", MAX_ENVIRONMENT_VALUE_CHARACTERS);
            total += key.length() + value.length();
            if (total > MAX_ENVIRONMENT_TOTAL_CHARACTERS) {
                throw new IllegalArgumentException(
                        "env exceeds " + MAX_ENVIRONMENT_TOTAL_CHARACTERS + " total characters");
            }
            result.put(key, value);
        }
        return Map.copyOf(result);
    }

    private static String bounded(String value, String field, int maximum) {
        if (value.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
        }
        return value;
    }
}
