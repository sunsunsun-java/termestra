package dev.termestra.team.application.port.out;

/** Latency hint for the durable delivery runtime; correctness never depends on a wake being observed. */
@FunctionalInterface
public interface DispatchDeliveryScheduler {
    void wake();
}
