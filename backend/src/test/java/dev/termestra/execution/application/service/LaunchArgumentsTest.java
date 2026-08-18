package dev.termestra.execution.application.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LaunchArgumentsTest {
    @Test void preservesDuplicatesFromUserArgumentsButDoesNotRepeatPresetPrefixValues() {
        assertEquals(List.of("--yolo", "-v", "-v"),
                LaunchArguments.prependUnique(List.of("--yolo"), List.of("--yolo", "-v", "-v")));
    }
}
