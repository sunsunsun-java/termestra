package dev.termestra.execution.application.port.out;

import java.util.function.*;
public interface PseudoTerminalHandle {
    long pid();
    void activate(Consumer<byte[]> output,IntConsumer exit);
    /**
     * Activates the PTY and reports adapter failures that prevent further reliable I/O.
     *
     * <p>The compatibility default is intentionally one-way: existing non-I/O test adapters can
     * keep implementing the two-listener form, while real adapters should override this overload
     * so a broken output pump enters the same supervised terminal transition as an explicit stop.</p>
     */
    default void activate(Consumer<byte[]> output,IntConsumer exit,
                          Consumer<RuntimeException> failure){
        activate(output,exit);
    }
    void write(byte[] input);
    void resize(int columns,int rows);
    void pauseOutput();
    void resumeOutput();
    void stop();
    /**
     * Requests bounded termination and reports whether the complete owned process tree is gone.
     *
     * <p>The default preserves compatibility with adapters whose {@link #stop()} operation already
     * has that contract. Process adapters that can observe descendants should override this method
     * and return their complete tree-termination result.</p>
     */
    default boolean stopAndConfirm(){
        stop();
        return !alive();
    }
    boolean alive();
}
