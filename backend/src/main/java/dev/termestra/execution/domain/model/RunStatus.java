package dev.termestra.execution.domain.model;

public enum RunStatus {
    STARTING("starting"), RUNNING("running"), EXITED("exited"), ERROR("error");
    private final String wireValue;
    RunStatus(String wireValue){this.wireValue=wireValue;}
    public String wireValue(){return wireValue;}
    public boolean active(){return this==STARTING||this==RUNNING;}
}
