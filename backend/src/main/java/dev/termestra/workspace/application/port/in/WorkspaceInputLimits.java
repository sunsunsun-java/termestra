package dev.termestra.workspace.application.port.in;

import dev.termestra.workspace.application.exception.InvalidWorkspacePath;

/** Write and hot-projection limits owned by the workspace application boundary. */
public final class WorkspaceInputLimits {
    public static final int MAX_NAME_CHARACTERS = 256;
    public static final int MAX_PATH_CHARACTERS = 4_096;

    private WorkspaceInputLimits() { }

    public static void validateName(String name) {
        if (name != null && name.trim().length() > MAX_NAME_CHARACTERS) {
            throw new IllegalArgumentException(
                    "Workspace name exceeds " + MAX_NAME_CHARACTERS + " characters");
        }
    }

    public static void validatePath(String path) {
        if (path != null && path.length() > MAX_PATH_CHARACTERS) {
            throw new InvalidWorkspacePath(
                    "Workspace path exceeds " + MAX_PATH_CHARACTERS + " characters");
        }
    }

    public static String boundedName(String name) {
        if (name == null || name.length() <= MAX_NAME_CHARACTERS) return name;
        int end = MAX_NAME_CHARACTERS;
        if (Character.isHighSurrogate(name.charAt(end - 1))
                && Character.isLowSurrogate(name.charAt(end))) end--;
        return name.substring(0, end);
    }
}
