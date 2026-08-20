package dev.termestra.execution.adapter.out.pty;

import com.pty4j.unix.PtyHelpers;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntBinaryOperator;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnixPtyProcessGroupTest {
    private static final Duration SHORT=Duration.ofMillis(20);

    @Test void esrchIsPositiveConfirmationThatTheOwnedGroupIsGone(){
        UnixPtyProcessGroup group=new UnixPtyProcessGroup(123,
                facade((ignoredGroup,ignoredSignal)->-1,()->3));

        assertTrue(group.terminate(SHORT,SHORT));
    }

    @Test void unknownPresenceFailureIsFailClosed(){
        UnixPtyProcessGroup group=new UnixPtyProcessGroup(123,
                facade((ignoredGroup,ignoredSignal)->-1,()->5));

        assertFalse(group.terminate(SHORT,SHORT));
    }

    @Test void epermPresenceProvesTheGroupExistsAndTerminationStillProceeds(){
        AtomicInteger calls=new AtomicInteger();
        AtomicInteger error=new AtomicInteger(1);
        UnixPtyProcessGroup group=new UnixPtyProcessGroup(123,facade((ignoredGroup,signal)->{
            int call=calls.incrementAndGet();
            if(call==1){error.set(1);return -1;} // signal-zero presence: EPERM
            if(call==2)return 0;                // SIGTERM accepted
            error.set(3);return -1;             // next presence: ESRCH
        },error::get));

        assertTrue(group.terminate(SHORT,SHORT));
    }

    @Test void epermSignalWaitsForBoundedDisappearanceInsteadOfFailingBeforeReaping(){
        AtomicInteger calls=new AtomicInteger();
        AtomicInteger error=new AtomicInteger();
        UnixPtyProcessGroup group=new UnixPtyProcessGroup(123,facade((ignoredGroup,signal)->{
            int call=calls.incrementAndGet();
            if(call==1)return 0;                  // presence probe
            if(call==2){error.set(1);return -1;} // SIGTERM: no signalable member yet
            error.set(3);return -1;              // bounded presence poll: group was reaped
        },error::get));

        assertTrue(group.terminate(SHORT,SHORT));
    }

    @Test void persistentEpermExhaustsBothDeadlinesAndFailsClosed(){
        UnixPtyProcessGroup group=new UnixPtyProcessGroup(123,
                facade((ignoredGroup,ignoredSignal)->-1,()->1));

        assertFalse(group.terminate(SHORT,SHORT));
    }

    @Test void anIncomingInterruptIsRestoredAfterBoundedTermination(){
        AtomicInteger calls=new AtomicInteger();
        UnixPtyProcessGroup group=new UnixPtyProcessGroup(123,facade((ignoredGroup,signal)->{
            int call=calls.incrementAndGet();
            if(call<=2)return 0; // presence probe, then SIGTERM
            return -1;          // ESRCH on the first wait probe
        },()->3));

        Thread.currentThread().interrupt();
        try{
            assertTrue(group.terminate(SHORT,SHORT));
            assertTrue(Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();}
    }

    private static PtyHelpers.OSFacade facade(IntBinaryOperator killpg,IntSupplier errno){
        return (PtyHelpers.OSFacade)Proxy.newProxyInstance(
                PtyHelpers.OSFacade.class.getClassLoader(),
                new Class<?>[]{PtyHelpers.OSFacade.class},
                (proxy,method,args)->{
                    if("killpg".equals(method.getName()))
                        return killpg.applyAsInt((Integer)args[0],(Integer)args[1]);
                    if("errno".equals(method.getName()))return errno.getAsInt();
                    if("toString".equals(method.getName()))return "FakePosixFacade";
                    Class<?> type=method.getReturnType();
                    if(type==boolean.class)return false;
                    if(type==byte.class)return (byte)0;
                    if(type==short.class)return (short)0;
                    if(type==int.class)return 0;
                    if(type==long.class)return 0L;
                    if(type==float.class)return 0F;
                    if(type==double.class)return 0D;
                    if(type==char.class)return (char)0;
                    return null;
                });
    }
}
