package dev.termestra.execution.application.port.out;

import java.util.List;
import java.util.Map;

public record LaunchPresetDescriptor(String id,String displayName,String command,List<String> arguments,
                                     Map<String,String> environment,String resumeArgsTemplate,
                                     String sessionIdCaptureJson,List<String> yoloArguments,
                                     List<String> modelArgumentTemplate,
                                     List<String> suggestedModels,boolean allowCustomModel,
                                     boolean available,long revision) {
    public LaunchPresetDescriptor {
        arguments=List.copyOf(arguments);
        environment=Map.copyOf(environment);
        yoloArguments=List.copyOf(yoloArguments);
        modelArgumentTemplate=modelArgumentTemplate==null?null:List.copyOf(modelArgumentTemplate);
        suggestedModels=List.copyOf(suggestedModels);
    }
}
