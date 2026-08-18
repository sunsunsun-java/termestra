package dev.termestra.team.application.exception;

/** A persisted dispatch cannot be projected safely into the team boundary. */
public final class InvalidDispatchRecord extends RuntimeException {
    public InvalidDispatchRecord() {
        super("Dispatch record is invalid");
    }

    public InvalidDispatchRecord(Throwable cause) {
        super("Dispatch record is invalid", cause);
    }
}
