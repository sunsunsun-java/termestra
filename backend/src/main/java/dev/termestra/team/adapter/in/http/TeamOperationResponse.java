package dev.termestra.team.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.termestra.team.application.port.in.TeamOperationResult;

public record TeamOperationResponse(boolean ok, @JsonProperty("dispatch_id") String dispatchId,
                                    boolean forwarded, @JsonProperty("forward_error") String forwardError) {
    static TeamOperationResponse from(TeamOperationResult result) {
        return new TeamOperationResponse(true,result.dispatchId(),result.forwarded(),result.forwardError());
    }
}
