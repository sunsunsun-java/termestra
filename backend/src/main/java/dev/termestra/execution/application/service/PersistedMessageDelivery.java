package dev.termestra.execution.application.service;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

final class PersistedMessageDelivery {
    private PersistedMessageDelivery() { }

    static void execute(LongSupplier persist,LongConsumer rollback,Delivery delivery)throws InterruptedException{
        long sequence=persist.getAsLong();
        try{delivery.deliver();}
        catch(InterruptedException error){rollbackPreserving(error,rollback,sequence);throw error;}
        catch(RuntimeException error){
            if(definitelyNotDelivered(error))rollbackPreserving(error,rollback,sequence);
            throw error;
        }
    }

    private static void rollbackPreserving(Throwable deliveryFailure,LongConsumer rollback,long sequence){
        try{rollback.accept(sequence);}
        catch(RuntimeException rollbackFailure){deliveryFailure.addSuppressed(rollbackFailure);}
    }

    private static boolean definitelyNotDelivered(RuntimeException error){
        return !(error instanceof InteractiveInputSubmitter.SubmissionException submission
                && submission.inputAttempted());
    }

    @FunctionalInterface interface Delivery { void deliver()throws InterruptedException; }
}
