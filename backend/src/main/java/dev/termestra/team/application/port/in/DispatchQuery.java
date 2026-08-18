package dev.termestra.team.application.port.in;

import java.util.List;
import java.util.Optional;

public interface DispatchQuery {
    List<DispatchSummaryView> listDispatches(String workspaceId, String state, int limit, int offset);
    List<DispatchSummaryView> listDeliveryIssues(String workspaceId, int limit);
    Optional<DispatchView> findDispatch(String workspaceId, String dispatchId);
}
