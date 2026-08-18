package dev.termestra.team.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
public record SendTaskResponse(boolean ok, @JsonProperty("dispatch_id") String dispatchId) { }
