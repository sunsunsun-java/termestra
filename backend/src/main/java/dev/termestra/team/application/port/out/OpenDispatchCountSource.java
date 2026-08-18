package dev.termestra.team.application.port.out;

import java.util.Map;

/** Loads the durable open-dispatch count used to initialize the in-memory projection. */
public interface OpenDispatchCountSource {
    int MAX_TRACKED_WORKERS_PER_WORKSPACE = TeamMemberRepository.MAX_MEMBERS_PER_WORKSPACE;
    int MAX_PENDING_TASKS_PER_WORKER = Integer.MAX_VALUE;

    Map<String, Integer> loadOpenCounts(String workspaceId);
}
