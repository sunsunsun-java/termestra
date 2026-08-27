package dev.termestra.workspace.application.port.in.registration;

import java.util.List;

public record RegistrationOptionsView(
        String canonicalPath,
        HeadView head,
        ChangeSummary changes,
        List<BranchView> branches,
        String nextCursor) {

    public sealed interface HeadView permits BranchHead, DetachedHead, UnbornHead { }
    public record BranchHead(String name, String oid) implements HeadView { }
    public record DetachedHead(String oid) implements HeadView { }
    public record UnbornHead(String name) implements HeadView { }
    public record ChangeSummary(String state, Integer count, String countAccuracy) { }
    public record BranchView(
            String name,
            boolean current,
            boolean selectable,
            String blockedReason,
            String selectionToken) { }
}
