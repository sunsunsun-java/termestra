package dev.termestra.execution.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

final class ExecutionRequests {
    private ExecutionRequests(){ }
    record Configure(String command,List<String> args,@JsonProperty("command_preset_id") String commandPresetId,
                     @JsonProperty("interactive_command") String interactiveCommand,Map<String,String> env) { }
    record Start(@JsonProperty("runtime_port") String runtimePort) { }
}
