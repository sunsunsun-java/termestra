package dev.termestra.team.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.termestra.team.application.port.in.TeamInputLimits;
import dev.termestra.team.application.port.in.TeamMemberView;

public record TeamMemberResponse(String id, String name, String role, String status,
                                 @JsonProperty("pending_task_count") int pendingTaskCount,
                                 @JsonProperty("last_pty_line") String lastPtyLine,
                                 @JsonProperty("command_preset_id") String commandPresetId) {
    static TeamMemberResponse from(TeamMemberView view) { return bounded(view,view.lastPtyLine()); }
    static TeamMemberResponse from(TeamMemberView view,String lastPtyLine) { return bounded(view,lastPtyLine); }
    private static TeamMemberResponse bounded(TeamMemberView view,String lastPtyLine) {
        return new TeamMemberResponse(view.id(),TeamInputLimits.boundedMemberName(view.name()),view.role(),
                view.status(),view.pendingTaskCount(),lastPtyLine,
                TeamInputLimits.boundedPresetId(view.commandPresetId()));
    }
}
