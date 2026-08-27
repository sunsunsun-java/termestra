package dev.termestra.execution.application.exception;

public final class ExecutionConflict extends RuntimeException {
    private final String errorCode;
    public ExecutionConflict(String message) { this(null,message,null); }
    public ExecutionConflict(String message, Throwable cause) { this(null,message,cause); }
    public ExecutionConflict(String errorCode,String message) { this(errorCode,message,null); }
    private ExecutionConflict(String errorCode,String message,Throwable cause) {
        super(message,cause);this.errorCode=errorCode;
    }
    public String errorCode(){return errorCode;}
}
