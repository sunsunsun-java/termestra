package dev.termestra.execution.application.port.out;

import java.util.*;

public interface AgentSessionCapture {
    Optional<CaptureSnapshot> snapshot(AgentDescriptor agent,String captureJson);
    Optional<String> findNew(CaptureSnapshot snapshot);
    default Optional<String> claimNew(CaptureSnapshot snapshot,String claimantId){return findNew(snapshot);}
    default void releaseClaims(String claimantId){}
    boolean exists(AgentDescriptor agent,String captureJson,String sessionId);

    record CaptureSnapshot(AgentDescriptor agent,String source,String pattern,String root,
                           Set<String> knownSessionIds,Map<String,String> environment) {
        public CaptureSnapshot { knownSessionIds=Set.copyOf(knownSessionIds);environment=Map.copyOf(environment); }
    }
}
