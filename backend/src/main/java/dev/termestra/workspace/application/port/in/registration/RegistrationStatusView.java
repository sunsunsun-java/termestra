package dev.termestra.workspace.application.port.in.registration;

public record RegistrationStatusView(
        String registrationId,
        String status,
        String workspaceId,
        String errorCode,
        Boolean sourceRevisionChanged,
        ObservedHead observedHead) {

    public sealed interface ObservedHead permits BranchHead, DetachedHead, UnbornHead { }

    public record BranchHead(String name, String oid) implements ObservedHead { }

    public record DetachedHead(String oid) implements ObservedHead { }

    public record UnbornHead(String name) implements ObservedHead { }
}
