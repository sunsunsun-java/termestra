package dev.termestra.workspace.application.port.in.browse;

import java.util.List;

public record BrowseView(
        boolean ok,
        String rootPath,
        String currentPath,
        String parentPath,
        List<BrowseEntryView> entries,
        boolean truncated,
        String error) { }
