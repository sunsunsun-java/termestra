package dev.termestra.platform.cli.team;

import java.util.Map;

record TeamEnvironment(String port, String workspaceId, String agentId, String token) {
    static TeamEnvironment from(Map<String,String> environment) {
        TeamEnvironment result = new TeamEnvironment(
                environment.get("TERMESTRA_PORT"),
                environment.get("TERMESTRA_WORKSPACE_ID"),
                environment.get("TERMESTRA_AGENT_ID"),
                environment.get("TERMESTRA_AGENT_TOKEN"));
        if (missing(result.port)||missing(result.workspaceId)||missing(result.agentId)||missing(result.token))
            throw new IllegalArgumentException("Missing required Termestra environment variables");
        return result;
    }
    String baseUrl(){return "http://127.0.0.1:"+port;}
    private static boolean missing(String value){return value==null||value.isBlank();}
}
