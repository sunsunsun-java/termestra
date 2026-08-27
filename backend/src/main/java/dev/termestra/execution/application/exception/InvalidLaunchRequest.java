package dev.termestra.execution.application.exception;

/** A launch contract failure with a stable public error code. */
public final class InvalidLaunchRequest extends IllegalArgumentException {
    private final String errorCode;

    public InvalidLaunchRequest(String errorCode,String detail) {
        super(errorCode+": "+detail);
        this.errorCode=errorCode;
    }

    public String errorCode(){return errorCode;}
}
