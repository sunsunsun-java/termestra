package dev.termestra.configuration.domain.model;
import java.util.*;
public record RoleTemplate(String id,String name,String roleType,String description,String defaultCommand,List<String> defaultArguments,Map<String,String> defaultEnvironment,boolean builtin){public RoleTemplate{defaultArguments=List.copyOf(Objects.requireNonNullElse(defaultArguments,List.of()));defaultEnvironment=Map.copyOf(Objects.requireNonNullElse(defaultEnvironment,Map.of()));}}
