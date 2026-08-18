package dev.termestra.execution.application.port.out;

import java.util.*;
public record ProcessLaunchRequest(List<String> command,String directory,Map<String,String> environment,int columns,int rows){
    public ProcessLaunchRequest{command=List.copyOf(command);environment=Map.copyOf(environment);}
}
