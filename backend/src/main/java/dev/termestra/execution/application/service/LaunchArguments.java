package dev.termestra.execution.application.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class LaunchArguments {
    private LaunchArguments() { }

    /** Prefix arguments win, while duplicates in the caller's own argument list remain meaningful. */
    static List<String> prependUnique(List<String> prefix, List<String> arguments) {
        List<String> result = new ArrayList<>(prefix);
        Set<String> prefixValues = new HashSet<>(prefix);
        for (String argument : arguments) {
            if (!prefixValues.contains(argument)) result.add(argument);
        }
        return result;
    }
}
