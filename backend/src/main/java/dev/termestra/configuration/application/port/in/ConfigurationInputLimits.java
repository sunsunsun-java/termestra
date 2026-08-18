package dev.termestra.configuration.application.port.in;

import dev.termestra.configuration.domain.model.CommandPreset;
import dev.termestra.configuration.domain.model.RoleTemplate;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Write limits and legacy-safe HTTP projection bounds for persisted configuration values. */
public final class ConfigurationInputLimits {
    public static final int MAX_DISPLAY_NAME_CHARACTERS = 128;
    public static final int MAX_COMMAND_CHARACTERS = 2_048;
    public static final int MAX_ARGUMENTS = 64;
    public static final int MAX_ARGUMENT_CHARACTERS = 1_024;
    public static final int MAX_ARGUMENT_TOTAL_CHARACTERS = 4_096;
    public static final int MAX_ENVIRONMENT_ENTRIES = 64;
    public static final int MAX_ENVIRONMENT_KEY_CHARACTERS = 128;
    public static final int MAX_ENVIRONMENT_VALUE_CHARACTERS = 2_048;
    public static final int MAX_ENVIRONMENT_TOTAL_CHARACTERS = 8_192;
    public static final int MAX_RESUME_TEMPLATE_CHARACTERS = 4_096;
    public static final int MAX_SESSION_CAPTURE_DEPTH = 8;
    public static final int MAX_SESSION_CAPTURE_COLLECTION_ENTRIES = 64;
    public static final int MAX_SESSION_CAPTURE_NODES = 256;
    public static final int MAX_SESSION_CAPTURE_TEXT_CHARACTERS = 8_192;
    public static final int MAX_SESSION_CAPTURE_STRING_CHARACTERS = 4_096;
    public static final int MAX_ROLE_NAME_CHARACTERS = 128;
    public static final int MAX_ROLE_TYPE_CHARACTERS = 64;
    public static final int MAX_ROLE_DESCRIPTION_CHARACTERS = 4_096;

    private ConfigurationInputLimits() { }

    public static void validate(CommandPreset value) {
        if (value == null) throw new IllegalArgumentException("Command preset is required");
        requireText(value.displayName(), "display_name", MAX_DISPLAY_NAME_CHARACTERS);
        requireText(value.command(), "command", MAX_COMMAND_CHARACTERS);
        validateStringList(value.arguments(), "args");
        validateEnvironment(value.environment(), "env");
        validateOptionalText(value.resumeArgsTemplate(), "resume_args_template",
                MAX_RESUME_TEMPLATE_CHARACTERS);
        if (value.sessionIdCapture() != null) validateStructured(value.sessionIdCapture());
        if (value.yoloArgsTemplate() != null) validateStringList(value.yoloArgsTemplate(), "yolo_args_template");
    }

    public static void validate(RoleTemplate value) {
        if (value == null) throw new IllegalArgumentException("Role template is required");
        requireText(value.name(), "name", MAX_ROLE_NAME_CHARACTERS);
        requireText(value.roleType(), "role_type", MAX_ROLE_TYPE_CHARACTERS);
        validateOptionalText(value.description(), "description", MAX_ROLE_DESCRIPTION_CHARACTERS);
        validateOptionalText(value.defaultCommand(), "default_command", MAX_COMMAND_CHARACTERS);
        validateStringList(value.defaultArguments(), "default_args");
        validateEnvironment(value.defaultEnvironment(), "default_env");
    }

    public static String boundedDisplayName(String value) {
        return bounded(value, MAX_DISPLAY_NAME_CHARACTERS);
    }

    public static String boundedCommand(String value) {
        return bounded(value, MAX_COMMAND_CHARACTERS);
    }

    public static String boundedResumeTemplate(String value) {
        return bounded(value, MAX_RESUME_TEMPLATE_CHARACTERS);
    }

    public static String boundedRoleName(String value) {
        return bounded(value, MAX_ROLE_NAME_CHARACTERS);
    }

    public static String boundedRoleType(String value) {
        return bounded(value, MAX_ROLE_TYPE_CHARACTERS);
    }

    public static String boundedRoleDescription(String value) {
        return bounded(value, MAX_ROLE_DESCRIPTION_CHARACTERS);
    }

    public static List<String> boundedArguments(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> result = new ArrayList<>(Math.min(values.size(), MAX_ARGUMENTS));
        int remaining = MAX_ARGUMENT_TOTAL_CHARACTERS;
        for (String value : values) {
            if (result.size() == MAX_ARGUMENTS || remaining == 0) break;
            String safe = value == null ? "" : value;
            int length = Math.min(Math.min(safe.length(), MAX_ARGUMENT_CHARACTERS), remaining);
            String bounded = prefix(safe, length);
            result.add(bounded);
            remaining -= bounded.length();
        }
        return List.copyOf(result);
    }

    public static Map<String, String> boundedEnvironment(Map<String, String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        int remaining = MAX_ENVIRONMENT_TOTAL_CHARACTERS;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (result.size() == MAX_ENVIRONMENT_ENTRIES || remaining == 0) break;
            String key = bounded(entry.getKey(), Math.min(MAX_ENVIRONMENT_KEY_CHARACTERS, remaining));
            remaining -= key.length();
            String value = entry.getValue() == null ? "" : entry.getValue();
            int valueLength = Math.min(Math.min(value.length(), MAX_ENVIRONMENT_VALUE_CHARACTERS), remaining);
            String boundedValue = prefix(value, valueLength);
            result.put(key, boundedValue);
            remaining -= boundedValue.length();
        }
        return Map.copyOf(result);
    }

    public static Map<String, Object> boundedSessionCapture(Map<String, Object> value) {
        if (value == null) return null;
        OutputBudget budget = new OutputBudget();
        Object bounded = boundStructured(value, 0, budget, new IdentityHashMap<>());
        if (bounded instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private static void validateStringList(List<String> values, String field) {
        if (values.size() > MAX_ARGUMENTS) {
            throw new IllegalArgumentException(field + " exceeds " + MAX_ARGUMENTS + " entries");
        }
        long total = 0;
        for (String value : values) {
            if (value.length() > MAX_ARGUMENT_CHARACTERS) {
                throw new IllegalArgumentException(
                        field + " entry exceeds " + MAX_ARGUMENT_CHARACTERS + " characters");
            }
            total += value.length();
            if (total > MAX_ARGUMENT_TOTAL_CHARACTERS) {
                throw new IllegalArgumentException(
                        field + " exceeds " + MAX_ARGUMENT_TOTAL_CHARACTERS + " total characters");
            }
        }
    }

    private static void validateEnvironment(Map<String, String> values, String field) {
        if (values.size() > MAX_ENVIRONMENT_ENTRIES) {
            throw new IllegalArgumentException(
                    field + " exceeds " + MAX_ENVIRONMENT_ENTRIES + " entries");
        }
        long total = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().length() > MAX_ENVIRONMENT_KEY_CHARACTERS) {
                throw new IllegalArgumentException(
                        field + " key exceeds " + MAX_ENVIRONMENT_KEY_CHARACTERS + " characters");
            }
            if (entry.getValue().length() > MAX_ENVIRONMENT_VALUE_CHARACTERS) {
                throw new IllegalArgumentException(
                        field + " value exceeds " + MAX_ENVIRONMENT_VALUE_CHARACTERS + " characters");
            }
            total += entry.getKey().length() + entry.getValue().length();
            if (total > MAX_ENVIRONMENT_TOTAL_CHARACTERS) {
                throw new IllegalArgumentException(
                        field + " exceeds " + MAX_ENVIRONMENT_TOTAL_CHARACTERS + " total characters");
            }
        }
    }

    private static void validateStructured(Map<String, Object> value) {
        StructuredBudget budget = new StructuredBudget();
        validateStructuredNode(value, 0, budget, new IdentityHashMap<>());
    }

    private static void validateStructuredNode(Object value, int depth, StructuredBudget budget,
                                               IdentityHashMap<Object, Boolean> visited) {
        if (depth > MAX_SESSION_CAPTURE_DEPTH) {
            throw new IllegalArgumentException(
                    "session_id_capture exceeds nesting depth " + MAX_SESSION_CAPTURE_DEPTH);
        }
        if (++budget.nodes > MAX_SESSION_CAPTURE_NODES) {
            throw new IllegalArgumentException(
                    "session_id_capture exceeds " + MAX_SESSION_CAPTURE_NODES + " values");
        }
        if (value == null || value instanceof Number || value instanceof Boolean) return;
        if (value instanceof String text) {
            addStructuredText(text, budget);
            return;
        }
        if (visited.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("session_id_capture must not contain cycles");
        }
        try {
            if (value instanceof Map<?, ?> map) {
                if (map.size() > MAX_SESSION_CAPTURE_COLLECTION_ENTRIES) {
                    throw new IllegalArgumentException("session_id_capture object exceeds " +
                            MAX_SESSION_CAPTURE_COLLECTION_ENTRIES + " entries");
                }
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("session_id_capture keys must be strings");
                    }
                    addStructuredText(key, budget);
                    validateStructuredNode(entry.getValue(), depth + 1, budget, visited);
                }
                return;
            }
            if (value instanceof List<?> list) {
                if (list.size() > MAX_SESSION_CAPTURE_COLLECTION_ENTRIES) {
                    throw new IllegalArgumentException("session_id_capture array exceeds " +
                            MAX_SESSION_CAPTURE_COLLECTION_ENTRIES + " entries");
                }
                for (Object item : list) validateStructuredNode(item, depth + 1, budget, visited);
                return;
            }
            throw new IllegalArgumentException("session_id_capture contains an unsupported value");
        } finally {
            visited.remove(value);
        }
    }

    private static void addStructuredText(String value, StructuredBudget budget) {
        if (value.length() > MAX_SESSION_CAPTURE_STRING_CHARACTERS) {
            throw new IllegalArgumentException("session_id_capture string exceeds " +
                    MAX_SESSION_CAPTURE_STRING_CHARACTERS + " characters");
        }
        budget.text += value.length();
        if (budget.text > MAX_SESSION_CAPTURE_TEXT_CHARACTERS) {
            throw new IllegalArgumentException("session_id_capture exceeds " +
                    MAX_SESSION_CAPTURE_TEXT_CHARACTERS + " text characters");
        }
    }

    private static Object boundStructured(Object value, int depth, OutputBudget budget,
                                          IdentityHashMap<Object, Boolean> visited) {
        if (budget.nodes >= MAX_SESSION_CAPTURE_NODES || depth > MAX_SESSION_CAPTURE_DEPTH) return null;
        budget.nodes++;
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof String text) return budget.take(text, MAX_SESSION_CAPTURE_STRING_CHARACTERS);
        if (visited.put(value, Boolean.TRUE) != null) return null;
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (result.size() == MAX_SESSION_CAPTURE_COLLECTION_ENTRIES || budget.exhausted()) break;
                    String key = budget.take(String.valueOf(entry.getKey()), MAX_SESSION_CAPTURE_STRING_CHARACTERS);
                    Object item = boundStructured(entry.getValue(), depth + 1, budget, visited);
                    if (item != null) result.put(key, item);
                }
                return result;
            }
            if (value instanceof List<?> list) {
                List<Object> result = new ArrayList<>();
                for (Object item : list) {
                    if (result.size() == MAX_SESSION_CAPTURE_COLLECTION_ENTRIES || budget.exhausted()) break;
                    result.add(boundStructured(item, depth + 1, budget, visited));
                }
                return result;
            }
            return budget.take(String.valueOf(value), MAX_SESSION_CAPTURE_STRING_CHARACTERS);
        } finally {
            visited.remove(value);
        }
    }

    private static void requireText(String value, String field, int maximum) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        validateOptionalText(value, field, maximum);
    }

    private static void validateOptionalText(String value, String field, int maximum) {
        if (value != null && value.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
        }
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

    private static final class StructuredBudget {
        private int nodes;
        private long text;
    }

    private static final class OutputBudget {
        private int nodes;
        private int text;

        private String take(String value, int perValueMaximum) {
            int remaining = Math.max(0, MAX_SESSION_CAPTURE_TEXT_CHARACTERS - text);
            int length = Math.min(Math.min(value.length(), perValueMaximum), remaining);
            String bounded = prefix(value, length);
            text += bounded.length();
            return bounded;
        }

        private boolean exhausted() {
            return nodes >= MAX_SESSION_CAPTURE_NODES || text >= MAX_SESSION_CAPTURE_TEXT_CHARACTERS;
        }
    }
}
