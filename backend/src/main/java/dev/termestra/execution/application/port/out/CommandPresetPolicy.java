package dev.termestra.execution.application.port.out;

import java.util.List;

@FunctionalInterface
public interface CommandPresetPolicy {
    List<String> yoloArguments(String commandPresetId,String command);
}
