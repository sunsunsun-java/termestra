package dev.termestra.team.domain.model;

public enum DeliveryState {
    PENDING("pending"),
    DELIVERING("delivering"),
    RETRY_WAIT("retry_wait"),
    SUBMITTED("submitted"),
    UNCERTAIN("uncertain"),
    FAILED("failed"),
    CLOSED("closed");

    private final String wireValue;

    DeliveryState(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }
}
