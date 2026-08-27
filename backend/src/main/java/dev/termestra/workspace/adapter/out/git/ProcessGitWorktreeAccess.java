package dev.termestra.workspace.adapter.out.git;

import dev.termestra.platform.process.BoundedProcessRunner;
import dev.termestra.workspace.application.exception.GitWorktreeAccessFailure;
import dev.termestra.workspace.application.port.out.GitWorktreeAccess;
import dev.termestra.workspace.domain.model.WorkspacePath;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ProcessGitWorktreeAccess implements GitWorktreeAccess {
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SWITCH_TIMEOUT = Duration.ofSeconds(20);
    private static final int QUERY_OUTPUT_LIMIT = 512 * 1_024;
    private static final int SWITCH_OUTPUT_LIMIT = 64 * 1_024;
    private static final int MAX_LOCAL_BRANCHES = 4_096;
    private final BoundedProcessRunner processes = new BoundedProcessRunner();

    public ProcessGitWorktreeAccess() { }

    @Override
    public Inspection inspect(WorkspacePath workspacePath) {
        Path requested = Path.of(workspacePath.value()).toAbsolutePath().normalize();
        BoundedProcessRunner.Result identity = query(requested,
                List.of("rev-parse", "--show-toplevel", "--git-common-dir"));
        requireComplete(identity, "GIT_WORKTREE_REQUIRED", "Selected directory is not a Git working tree");
        String[] identityLines = identity.output().strip().split("\\R");
        if (identityLines.length < 2) {
            throw new GitWorktreeAccessFailure(
                    "GIT_QUERY_FAILED", "Git did not return a complete worktree identity", true);
        }
        Path root = Path.of(identityLines[0]).toAbsolutePath().normalize();
        if (!root.equals(requested)) {
            throw new GitWorktreeAccessFailure(
                    "GIT_WORKTREE_ROOT_REQUIRED",
                    "Workspace path must be the Git worktree root: " + root,
                    false);
        }
        Path common = Path.of(identityLines[1]);
        if (!common.isAbsolute()) common = root.resolve(common);
        common = common.toAbsolutePath().normalize();

        Head head = readHead(root);
        Set<String> occupied = occupiedBranches(root);
        List<LocalBranch> branches = readBranches(root, occupied);
        ChangeSummary changes = readChanges(root);
        return new Inspection(root.toString(), common.toString(), head, changes, branches);
    }

    @Override
    public CheckoutOutcome switchToExistingLocalBranch(
            WorkspacePath path, String branch, String expectedOid) {
        Inspection before = inspect(path);
        LocalBranch target = before.localBranches().stream()
                .filter(value -> value.name().equals(branch))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return new Rejected("GIT_SELECTION_STALE", "Selected local branch no longer exists", before);
        }
        if (!target.oid().equals(expectedOid)) {
            return new Rejected("GIT_SELECTION_STALE",
                    "Selected local branch changed after it was inspected", before);
        }
        if (target.checkedOutElsewhere()) {
            return new Rejected("GIT_BRANCH_CHECKED_OUT_ELSEWHERE",
                    "Selected branch is checked out by another worktree", before);
        }
        if (before.head() instanceof BranchHead current
                && current.name().equals(branch) && current.oid().equals(expectedOid)) {
            return new Applied(before);
        }
        final BoundedProcessRunner.Result result;
        try {
            result = processes.run(
                    List.of("git", "-C", before.worktreeRoot(), "switch", "--no-guess", "--", branch),
                    SWITCH_TIMEOUT, SWITCH_OUTPUT_LIMIT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Unknown("GIT_OPERATION_OUTCOME_UNKNOWN", "Git switch was interrupted",
                    inspectBestEffort(path, before));
        } catch (IOException error) {
            return new Unknown("GIT_OPERATION_OUTCOME_UNKNOWN", "Git switch could not be completed",
                    inspectBestEffort(path, before));
        }
        Inspection observed = inspectBestEffort(path, before);
        if (result.timedOut() || result.outputTruncated()) {
            return new Unknown("GIT_OPERATION_OUTCOME_UNKNOWN", bounded(result.output()), observed);
        }
        if (result.exitCode() != 0) {
            return new Rejected("GIT_SWITCH_REJECTED", bounded(result.output()), observed);
        }
        if (observed.head() instanceof BranchHead current
                && current.name().equals(branch) && current.oid().equals(expectedOid)) {
            return new Applied(observed);
        }
        return new Unknown("GIT_OPERATION_OUTCOME_UNKNOWN",
                "Git returned success but the requested branch could not be observed", observed);
    }

    private Head readHead(Path root) {
        BoundedProcessRunner.Result symbolic = query(root,
                List.of("symbolic-ref", "--quiet", "--short", "HEAD"));
        BoundedProcessRunner.Result oid = query(root,
                List.of("rev-parse", "--verify", "HEAD"));
        requireReadable(symbolic, "Git symbolic HEAD inspection did not complete");
        requireReadable(oid, "Git HEAD inspection did not complete");
        String headOid = successfulValue(oid);
        String branch = successfulValue(symbolic);
        if (branch != null && headOid != null) return new BranchHead(branch, headOid);
        if (branch != null) return new UnbornHead(branch);
        if (headOid != null) return new DetachedHead(headOid);
        throw new GitWorktreeAccessFailure("GIT_QUERY_FAILED", "Git HEAD could not be inspected", true);
    }

    private List<LocalBranch> readBranches(Path root, Set<String> occupied) {
        BoundedProcessRunner.Result result = query(root,
                List.of("for-each-ref", "--sort=refname",
                        "--format=%(refname:short)%09%(objectname)", "refs/heads"));
        requireComplete(result, "GIT_QUERY_FAILED", "Local Git branches could not be listed");
        List<LocalBranch> branches = new ArrayList<>();
        if (!result.output().isBlank()) {
            for (String line : result.output().split("\\R")) {
                int separator = line.indexOf('\t');
                if (separator <= 0 || separator == line.length() - 1) continue;
                String name = line.substring(0, separator);
                String oid = line.substring(separator + 1);
                branches.add(new LocalBranch(name, oid, occupied.contains(name)));
                if (branches.size() > MAX_LOCAL_BRANCHES) {
                    throw new GitWorktreeAccessFailure(
                            "GIT_BRANCH_LIMIT_EXCEEDED",
                            "Repository has more than " + MAX_LOCAL_BRANCHES + " local branches",
                            false);
                }
            }
        }
        return List.copyOf(branches);
    }

    private Set<String> occupiedBranches(Path currentRoot) {
        BoundedProcessRunner.Result result = query(currentRoot,
                List.of("worktree", "list", "--porcelain"));
        requireComplete(result, "GIT_QUERY_FAILED", "Git worktrees could not be inspected");
        Set<String> occupied = new HashSet<>();
        String worktree = null;
        for (String line : result.output().split("\\R")) {
            if (line.startsWith("worktree ")) worktree = line.substring("worktree ".length());
            if (line.startsWith("branch refs/heads/") && worktree != null) {
                String branch = line.substring("branch refs/heads/".length());
                Path path = Path.of(worktree).toAbsolutePath().normalize();
                if (!path.equals(currentRoot)) occupied.add(branch);
            }
            if (line.isBlank()) worktree = null;
        }
        return occupied;
    }

    private ChangeSummary readChanges(Path root) {
        BoundedProcessRunner.Result result = query(root,
                List.of("status", "--porcelain=v1", "--untracked-files=normal"));
        if (result.timedOut() || result.exitCode() != 0) {
            return new ChangeSummary(ChangeState.UNKNOWN, null, "unknown");
        }
        int count = result.output().isBlank() ? 0 : result.output().split("\\R").length;
        return new ChangeSummary(count > 0 || result.outputTruncated()
                        ? ChangeState.DIRTY : ChangeState.CLEAN, count,
                result.outputTruncated() ? "lower_bound" : "exact");
    }

    private BoundedProcessRunner.Result query(Path root, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(root.toString());
        command.addAll(arguments);
        try {
            return processes.run(command, QUERY_TIMEOUT, QUERY_OUTPUT_LIMIT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GitWorktreeAccessFailure(
                    "GIT_QUERY_TIMEOUT", "Git inspection was interrupted", true, interrupted);
        } catch (IOException error) {
            throw new GitWorktreeAccessFailure(
                    "GIT_UNAVAILABLE", "Git is not available", false, error);
        }
    }

    private static void requireComplete(BoundedProcessRunner.Result result,
                                        String errorCode, String message) {
        if (result.timedOut()) {
            throw new GitWorktreeAccessFailure("GIT_QUERY_TIMEOUT", message, true);
        }
        if (result.outputTruncated()) {
            throw new GitWorktreeAccessFailure("GIT_QUERY_LIMIT_EXCEEDED", message, false);
        }
        if (result.exitCode() != 0) {
            throw new GitWorktreeAccessFailure(errorCode, message, false);
        }
    }

    private static String successfulValue(BoundedProcessRunner.Result result) {
        if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0) return null;
        String value = result.output().trim();
        return value.isEmpty() ? null : value;
    }

    private static void requireReadable(BoundedProcessRunner.Result result, String message) {
        if (result.timedOut()) {
            throw new GitWorktreeAccessFailure("GIT_QUERY_TIMEOUT", message, true);
        }
        if (result.outputTruncated()) {
            throw new GitWorktreeAccessFailure("GIT_QUERY_LIMIT_EXCEEDED", message, false);
        }
    }

    private Inspection inspectBestEffort(WorkspacePath path, Inspection fallback) {
        try {
            return inspect(path);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String bounded(String diagnostic) {
        if (diagnostic == null || diagnostic.isBlank()) return "Git rejected the branch switch";
        String value = diagnostic.strip();
        return value.length() <= 2_048 ? value : value.substring(0, 2_048);
    }
}
