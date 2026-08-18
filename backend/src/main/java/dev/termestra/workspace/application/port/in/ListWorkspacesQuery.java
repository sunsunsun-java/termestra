package dev.termestra.workspace.application.port.in;

import java.util.List;

public interface ListWorkspacesQuery {
    List<WorkspaceView> list();
}
