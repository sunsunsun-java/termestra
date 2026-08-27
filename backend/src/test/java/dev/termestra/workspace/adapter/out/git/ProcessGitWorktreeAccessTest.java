package dev.termestra.workspace.adapter.out.git;

import dev.termestra.workspace.application.exception.GitWorktreeAccessFailure;
import dev.termestra.workspace.application.port.out.GitWorktreeAccess;
import dev.termestra.workspace.domain.model.WorkspacePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProcessGitWorktreeAccessTest {
    @TempDir Path temporaryDirectory;

    @Test void listsOnlyLocalBranchesAndSwitchesToAnExistingSelection() throws Exception {
        Path repository = repository("switch-existing");
        git(repository, "branch", "feature/local");
        Files.writeString(repository.resolve("untracked.txt"), "local change");
        ProcessGitWorktreeAccess access = new ProcessGitWorktreeAccess();

        GitWorktreeAccess.Inspection before = access.inspect(new WorkspacePath(repository.toString()));

        assertInstanceOf(GitWorktreeAccess.BranchHead.class, before.head());
        assertEquals("main", ((GitWorktreeAccess.BranchHead) before.head()).name());
        assertEquals(List.of("feature/local", "main"), before.localBranches().stream()
                .map(GitWorktreeAccess.LocalBranch::name).toList());
        assertEquals(GitWorktreeAccess.ChangeState.DIRTY, before.changes().state());
        assertEquals("exact", before.changes().countAccuracy());

        GitWorktreeAccess.CheckoutOutcome outcome = access.switchToExistingLocalBranch(
                new WorkspacePath(repository.toString()), "feature/local",
                branch(before, "feature/local").oid());

        GitWorktreeAccess.Applied applied = assertInstanceOf(GitWorktreeAccess.Applied.class, outcome);
        assertEquals("feature/local", ((GitWorktreeAccess.BranchHead) applied.observed().head()).name());
        assertEquals("feature/local", git(repository, "branch", "--show-current").strip());
    }

    @Test void marksABranchCheckedOutByAnotherWorktreeAsUnavailable() throws Exception {
        Path repository = repository("occupied-branch");
        git(repository, "branch", "occupied");
        Path worktree = temporaryDirectory.resolve("other-worktree");
        git(repository, "worktree", "add", worktree.toString(), "occupied");

        GitWorktreeAccess.Inspection inspection = new ProcessGitWorktreeAccess()
                .inspect(new WorkspacePath(repository.toString()));

        GitWorktreeAccess.LocalBranch occupied = inspection.localBranches().stream()
                .filter(branch -> branch.name().equals("occupied")).findFirst().orElseThrow();
        assertTrue(occupied.checkedOutElsewhere());
        assertInstanceOf(GitWorktreeAccess.Rejected.class,
                new ProcessGitWorktreeAccess().switchToExistingLocalBranch(
                        new WorkspacePath(repository.toString()), "occupied", occupied.oid()));
    }

    @Test void rejectsASelectionWhoseCommitChangedAfterInspection() throws Exception {
        Path repository = repository("advanced-branch");
        git(repository, "branch", "feature");
        ProcessGitWorktreeAccess access = new ProcessGitWorktreeAccess();
        GitWorktreeAccess.Inspection selected = access.inspect(new WorkspacePath(repository.toString()));
        String selectedOid = branch(selected, "feature").oid();
        Files.writeString(repository.resolve("README.md"), "advanced");
        git(repository, "add", "README.md");
        git(repository, "commit", "-m", "advance main");
        git(repository, "branch", "-f", "feature", "HEAD");

        GitWorktreeAccess.CheckoutOutcome outcome = access.switchToExistingLocalBranch(
                new WorkspacePath(repository.toString()), "feature", selectedOid);

        GitWorktreeAccess.Rejected rejected =
                assertInstanceOf(GitWorktreeAccess.Rejected.class, outcome);
        assertEquals("GIT_SELECTION_STALE", rejected.errorCode());
        assertEquals("main", git(repository, "branch", "--show-current").strip());
    }

    @Test void rejectsARepositorySubdirectoryAsAWorkspaceRoot() throws Exception {
        Path repository = repository("root-only");
        Path nested = Files.createDirectory(repository.resolve("nested"));

        GitWorktreeAccessFailure failure = assertThrows(GitWorktreeAccessFailure.class,
                () -> new ProcessGitWorktreeAccess().inspect(new WorkspacePath(nested.toString())));

        assertEquals("GIT_WORKTREE_ROOT_REQUIRED", failure.errorCode());
    }

    private Path repository(String name) throws Exception {
        Path path = Files.createDirectory(temporaryDirectory.resolve(name)).toRealPath();
        git(path, "init", "-b", "main");
        git(path, "config", "user.email", "test@termestra.dev");
        git(path, "config", "user.name", "Termestra Test");
        Files.writeString(path.resolve("README.md"), "test");
        git(path, "add", "README.md");
        git(path, "commit", "-m", "initial");
        return path;
    }

    private static GitWorktreeAccess.LocalBranch branch(
            GitWorktreeAccess.Inspection inspection, String name) {
        return inspection.localBranches().stream()
                .filter(branch -> branch.name().equals(name)).findFirst().orElseThrow();
    }

    private static String git(Path path, String... arguments) throws IOException, InterruptedException {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(path.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), () -> String.join(" ", command) + "\n" + output);
        return output;
    }
}
