package dev.termestra.workspace.application.port.out;

import dev.termestra.workspace.domain.model.WorkspacePath;

import java.util.List;

public interface GitWorktreeAccess {
    Inspection inspect(WorkspacePath path);
    CheckoutOutcome switchToExistingLocalBranch(WorkspacePath path, String branch, String expectedOid);

    record Inspection(
            String worktreeRoot,
            String commonGitDirectory,
            Head head,
            ChangeSummary changes,
            List<LocalBranch> localBranches) { }

    sealed interface Head permits BranchHead, DetachedHead, UnbornHead { }
    record BranchHead(String name, String oid) implements Head { }
    record DetachedHead(String oid) implements Head { }
    record UnbornHead(String name) implements Head { }
    enum ChangeState { CLEAN, DIRTY, UNKNOWN }
    record ChangeSummary(ChangeState state, Integer count, String countAccuracy) { }
    record LocalBranch(String name, String oid, boolean checkedOutElsewhere) { }

    sealed interface CheckoutOutcome permits Applied, Rejected, Unknown { }
    record Applied(Inspection observed) implements CheckoutOutcome { }
    record Rejected(String errorCode, String diagnostic, Inspection observed) implements CheckoutOutcome { }
    record Unknown(String errorCode, String diagnostic, Inspection observed) implements CheckoutOutcome { }
}
