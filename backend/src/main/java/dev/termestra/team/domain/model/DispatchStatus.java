package dev.termestra.team.domain.model;

public enum DispatchStatus {
    QUEUED("queued"),
    SUBMITTED("submitted"),
    REPORTED("reported"),
    CANCELLED("cancelled");

    private final String wireValue;

    DispatchStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static DispatchStatus parse(String value) {
        for (DispatchStatus status : values()) if (status.wireValue.equals(value)) return status;
        throw new IllegalArgumentException("Unsupported dispatch status: " + value);
    }
}
