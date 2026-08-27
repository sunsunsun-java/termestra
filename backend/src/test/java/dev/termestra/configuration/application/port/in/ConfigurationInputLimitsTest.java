package dev.termestra.configuration.application.port.in;

import dev.termestra.configuration.domain.model.CommandPreset;
import dev.termestra.configuration.domain.model.ModelCapability;
import dev.termestra.configuration.domain.model.RoleTemplate;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationInputLimitsTest {
    @Test void rejectsRepeatedModelPlaceholdersWithinOneArgument(){
        CommandPreset preset=new CommandPreset(null,"Model","tool",List.of(),Map.of(),null,null,
                List.of(),false,new ModelCapability(
                        List.of("--model={model_id}:{model_id}"),List.of("model"),true),1);
        assertThrows(IllegalArgumentException.class,()->ConfigurationInputLimits.validate(preset));
    }

    @Test void acceptsCommandAndRoleValuesExactlyAtTheirWriteBoundaries() {
        Map<String, String> environment = Map.of(
                "A", "a".repeat(2_047), "B", "b".repeat(2_047),
                "C", "c".repeat(2_047), "D", "d".repeat(2_047));
        List<String> arguments = Collections.nCopies(4,
                "a".repeat(ConfigurationInputLimits.MAX_ARGUMENT_CHARACTERS));
        CommandPreset preset = new CommandPreset(null,
                "p".repeat(ConfigurationInputLimits.MAX_DISPLAY_NAME_CHARACTERS),
                "c".repeat(ConfigurationInputLimits.MAX_COMMAND_CHARACTERS),
                arguments, environment,
                "r".repeat(ConfigurationInputLimits.MAX_RESUME_TEMPLATE_CHARACTERS),
                Map.of("pattern", "s".repeat(1_000)), arguments, false);
        RoleTemplate role = new RoleTemplate(null,
                "n".repeat(ConfigurationInputLimits.MAX_ROLE_NAME_CHARACTERS),
                "t".repeat(ConfigurationInputLimits.MAX_ROLE_TYPE_CHARACTERS),
                "d".repeat(ConfigurationInputLimits.MAX_ROLE_DESCRIPTION_CHARACTERS),
                "c".repeat(ConfigurationInputLimits.MAX_COMMAND_CHARACTERS),
                arguments, environment, false);

        ConfigurationInputLimits.validate(preset);
        ConfigurationInputLimits.validate(role);
    }

    @Test void rejectsArgumentAndEnvironmentCountItemAndAggregateAmplification() {
        assertInvalidPreset(Collections.nCopies(ConfigurationInputLimits.MAX_ARGUMENTS + 1, "a"), Map.of());
        assertInvalidPreset(List.of("a".repeat(ConfigurationInputLimits.MAX_ARGUMENT_CHARACTERS + 1)), Map.of());
        assertInvalidPreset(Collections.nCopies(5,
                "a".repeat(ConfigurationInputLimits.MAX_ARGUMENT_CHARACTERS)), Map.of());

        Map<String, String> tooManyEnvironmentValues = new LinkedHashMap<>();
        for (int index = 0; index <= ConfigurationInputLimits.MAX_ENVIRONMENT_ENTRIES; index++) {
            tooManyEnvironmentValues.put("K" + index, "v");
        }
        assertInvalidPreset(List.of(), tooManyEnvironmentValues);
        assertInvalidPreset(List.of(), Map.of(
                "k".repeat(ConfigurationInputLimits.MAX_ENVIRONMENT_KEY_CHARACTERS + 1), "v"));
        assertInvalidPreset(List.of(), Map.of("K",
                "v".repeat(ConfigurationInputLimits.MAX_ENVIRONMENT_VALUE_CHARACTERS + 1)));
        assertInvalidPreset(List.of(), Map.of(
                "A", "a".repeat(2_048), "B", "b".repeat(2_048),
                "C", "c".repeat(2_048), "D", "d".repeat(2_048)));
    }

    @Test void rejectsOversizedAndCyclicSessionCaptureStructures() {
        assertThrows(IllegalArgumentException.class, () -> ConfigurationInputLimits.validate(preset(
                List.of(), Map.of(), Map.of("pattern", "x".repeat(
                        ConfigurationInputLimits.MAX_SESSION_CAPTURE_STRING_CHARACTERS + 1)))));

        Map<String, Object> current = new LinkedHashMap<>();
        Map<String, Object> root = current;
        for (int depth = 0; depth <= ConfigurationInputLimits.MAX_SESSION_CAPTURE_DEPTH; depth++) {
            Map<String, Object> child = new LinkedHashMap<>();
            current.put("child", child);
            current = child;
        }
        assertThrows(IllegalArgumentException.class,
                () -> ConfigurationInputLimits.validate(preset(List.of(), Map.of(), root)));

        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("cycle", cycle);
        assertThrows(IllegalArgumentException.class,
                () -> ConfigurationInputLimits.validate(preset(List.of(), Map.of(), cycle)));
    }

    @Test void boundsLegacyCollectionsWithPerValueAndAggregateBudgets() {
        List<String> arguments = ConfigurationInputLimits.boundedArguments(Collections.nCopies(
                ConfigurationInputLimits.MAX_ARGUMENTS + 20,
                "a".repeat(ConfigurationInputLimits.MAX_ARGUMENT_CHARACTERS + 20)));
        assertTrue(arguments.size() <= ConfigurationInputLimits.MAX_ARGUMENTS);
        assertTrue(arguments.stream().allMatch(value ->
                value.length() <= ConfigurationInputLimits.MAX_ARGUMENT_CHARACTERS));
        assertTrue(arguments.stream().mapToInt(String::length).sum()
                <= ConfigurationInputLimits.MAX_ARGUMENT_TOTAL_CHARACTERS);

        Map<String, String> source = new LinkedHashMap<>();
        for (int index = 0; index < ConfigurationInputLimits.MAX_ENVIRONMENT_ENTRIES + 20; index++) {
            source.put("K" + index, "v".repeat(
                    ConfigurationInputLimits.MAX_ENVIRONMENT_VALUE_CHARACTERS + 20));
        }
        Map<String, String> environment = ConfigurationInputLimits.boundedEnvironment(source);
        assertTrue(environment.size() <= ConfigurationInputLimits.MAX_ENVIRONMENT_ENTRIES);
        assertTrue(environment.entrySet().stream().allMatch(entry ->
                entry.getKey().length() <= ConfigurationInputLimits.MAX_ENVIRONMENT_KEY_CHARACTERS
                        && entry.getValue().length()
                        <= ConfigurationInputLimits.MAX_ENVIRONMENT_VALUE_CHARACTERS));
        assertTrue(environment.entrySet().stream().mapToInt(entry ->
                entry.getKey().length() + entry.getValue().length()).sum()
                <= ConfigurationInputLimits.MAX_ENVIRONMENT_TOTAL_CHARACTERS);
        assertEquals(ConfigurationInputLimits.MAX_ENVIRONMENT_ENTRIES + 20, source.size());

        Map<String, Object> legacySession = new LinkedHashMap<>();
        legacySession.put("pattern",
                "s".repeat(ConfigurationInputLimits.MAX_SESSION_CAPTURE_TEXT_CHARACTERS * 2));
        legacySession.put("items", Collections.nCopies(
                ConfigurationInputLimits.MAX_SESSION_CAPTURE_COLLECTION_ENTRIES * 2, "value"));
        Map<String, Object> session = ConfigurationInputLimits.boundedSessionCapture(legacySession);
        assertEquals(ConfigurationInputLimits.MAX_SESSION_CAPTURE_STRING_CHARACTERS,
                session.get("pattern").toString().length());
        assertTrue(((List<?>) session.get("items")).size()
                <= ConfigurationInputLimits.MAX_SESSION_CAPTURE_COLLECTION_ENTRIES);
    }

    @Test void neverSplitsUnicodeSurrogatePairsWhileBoundingLegacyValues() {
        String display="d".repeat(ConfigurationInputLimits.MAX_DISPLAY_NAME_CHARACTERS-1)+"😀tail";
        String argument="a".repeat(ConfigurationInputLimits.MAX_ARGUMENT_CHARACTERS-1)+"😀tail";
        String environmentKey="k".repeat(ConfigurationInputLimits.MAX_ENVIRONMENT_KEY_CHARACTERS-1)+"😀tail";
        String environmentValue="e".repeat(ConfigurationInputLimits.MAX_ENVIRONMENT_VALUE_CHARACTERS-1)+"😀tail";
        String structured="s".repeat(ConfigurationInputLimits.MAX_SESSION_CAPTURE_STRING_CHARACTERS-1)+"😀tail";

        String boundedDisplay=ConfigurationInputLimits.boundedDisplayName(display);
        String boundedArgument=ConfigurationInputLimits.boundedArguments(List.of(argument)).getFirst();
        Map.Entry<String,String> boundedEnvironment=ConfigurationInputLimits.boundedEnvironment(
                Map.of(environmentKey,environmentValue)).entrySet().iterator().next();
        String boundedStructured=ConfigurationInputLimits.boundedSessionCapture(
                Map.of("pattern",structured)).get("pattern").toString();

        assertEquals(false,Character.isHighSurrogate(boundedDisplay.charAt(boundedDisplay.length()-1)));
        assertEquals(false,Character.isHighSurrogate(boundedArgument.charAt(boundedArgument.length()-1)));
        assertEquals(false,Character.isHighSurrogate(boundedEnvironment.getKey().charAt(
                boundedEnvironment.getKey().length()-1)));
        assertEquals(false,Character.isHighSurrogate(boundedEnvironment.getValue().charAt(
                boundedEnvironment.getValue().length()-1)));
        assertEquals(false,Character.isHighSurrogate(boundedStructured.charAt(boundedStructured.length()-1)));
        assertTrue(boundedDisplay.length()<=ConfigurationInputLimits.MAX_DISPLAY_NAME_CHARACTERS);
        assertTrue(boundedArgument.length()<=ConfigurationInputLimits.MAX_ARGUMENT_CHARACTERS);
        assertTrue(boundedEnvironment.getKey().length()<=ConfigurationInputLimits.MAX_ENVIRONMENT_KEY_CHARACTERS);
        assertTrue(boundedEnvironment.getValue().length()<=ConfigurationInputLimits.MAX_ENVIRONMENT_VALUE_CHARACTERS);
        assertTrue(boundedStructured.length()<=ConfigurationInputLimits.MAX_SESSION_CAPTURE_STRING_CHARACTERS);
    }

    private static void assertInvalidPreset(List<String> arguments, Map<String, String> environment) {
        assertThrows(IllegalArgumentException.class,
                () -> ConfigurationInputLimits.validate(preset(arguments, environment, null)));
    }

    private static CommandPreset preset(List<String> arguments, Map<String, String> environment,
                                        Map<String, Object> capture) {
        return new CommandPreset(null, "Preset", "tool", arguments, environment,
                null, capture, null, false);
    }
}
