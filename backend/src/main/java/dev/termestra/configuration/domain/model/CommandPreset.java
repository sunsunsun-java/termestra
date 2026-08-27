package dev.termestra.configuration.domain.model;

import java.util.*;

public record CommandPreset(String id,String displayName,String command,List<String> arguments,
                            Map<String,String> environment,String resumeArgsTemplate,
                            Map<String,Object> sessionIdCapture,List<String> yoloArgsTemplate,
                            boolean builtin,ModelCapability modelCapability,long revision) {
    public CommandPreset {
        arguments=List.copyOf(Objects.requireNonNullElse(arguments,List.of()));
        environment=Map.copyOf(Objects.requireNonNullElse(environment,Map.of()));
        sessionIdCapture=sessionIdCapture==null?null:Map.copyOf(sessionIdCapture);
        yoloArgsTemplate=yoloArgsTemplate==null?null:List.copyOf(yoloArgsTemplate);
        if(revision<1)throw new IllegalArgumentException("revision must be positive");
    }

    public CommandPreset(String id,String displayName,String command,List<String> arguments,
                         Map<String,String> environment,String resumeArgsTemplate,
                         Map<String,Object> sessionIdCapture,List<String> yoloArgsTemplate,
                         boolean builtin) {
        this(id,displayName,command,arguments,environment,resumeArgsTemplate,sessionIdCapture,
                yoloArgsTemplate,builtin,null,1);
    }
}
