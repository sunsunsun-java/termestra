package dev.termestra.execution.application.service;

import dev.termestra.execution.application.port.in.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class RunOutputHub {
    private static final Logger LOG=LoggerFactory.getLogger(RunOutputHub.class);
    private final ConcurrentHashMap<String,CopyOnWriteArrayList<SequencedListener>> listeners=new ConcurrentHashMap<>();

    RunOutputSubscription subscribe(String runId,long afterSequence,Consumer<String> listener){
        SequencedListener sequenced=new SequencedListener(afterSequence,listener);
        listeners.computeIfAbsent(runId,ignored->new CopyOnWriteArrayList<>()).add(sequenced);
        return ()->{var values=listeners.get(runId);if(values!=null){values.remove(sequenced);if(values.isEmpty())listeners.remove(runId,values);}};
    }

    void publish(String runId,long sequence,String text){
        CopyOnWriteArrayList<SequencedListener> subscribers=listeners.get(runId);
        if(subscribers==null)return;
        for(SequencedListener listener:subscribers){
            try{listener.accept(sequence,text);}
            catch(RuntimeException failure){
                subscribers.remove(listener);
                LOG.warn("Detached failed terminal output listener for run {}",runId,failure);
            }
        }
        if(subscribers.isEmpty())listeners.remove(runId,subscribers);
    }
    void clear(String runId){listeners.remove(runId);}

    private static final class SequencedListener {
        private final AtomicLong cursor;
        private final Consumer<String> listener;

        private SequencedListener(long afterSequence,Consumer<String> listener){
            this.cursor=new AtomicLong(afterSequence);
            this.listener=Objects.requireNonNull(listener);
        }

        private void accept(long sequence,String text){
            long previous=cursor.get();
            while(sequence>previous){
                if(cursor.compareAndSet(previous,sequence)){
                    listener.accept(text);
                    return;
                }
                previous=cursor.get();
            }
        }
    }
}
