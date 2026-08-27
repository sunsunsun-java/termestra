package dev.termestra.workspace.application.port.in.registration;

public sealed interface RevisionSelection permits RevisionSelection.Current, RevisionSelection.LocalBranch {
    String selectionToken();

    record Current(String selectionToken) implements RevisionSelection { }

    record LocalBranch(String name, String selectionToken) implements RevisionSelection {
        public LocalBranch {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("branch must not be blank");
            if (name.length() > 1_024) throw new IllegalArgumentException("branch exceeds 1024 characters");
            if (selectionToken == null || selectionToken.isBlank()) {
                throw new IllegalArgumentException("selection_token must not be blank");
            }
        }
    }
}
