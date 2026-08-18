package dev.termestra.team.application.exception;

/** A persisted member cannot be projected safely into the team domain. */
public final class InvalidTeamMemberRecord extends RuntimeException {
    public InvalidTeamMemberRecord() {
        super("Team member record is invalid");
    }

    public InvalidTeamMemberRecord(Throwable cause) {
        super("Team member record is invalid", cause);
    }
}
