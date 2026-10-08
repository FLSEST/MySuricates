package com.automation.concurrency;

import com.automation.testsupport.ParkedThreads;
import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleFlightTest {

    private final SingleFlight<String, Object> flight = new SingleFlight<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final CountDownLatch leaderInside = new CountDownLatch(1);
    private final CountDownLatch releaseLeader = new CountDownLatch(1);

    /** Leader computation: counts, signals that it runs, then waits until the test lets it finish. */
    private Object blockingComputation(Object result) {
        calls.incrementAndGet();
        leaderInside.countDown();
        ParkedThreads.await(releaseLeader);
        return result;
    }

    @Test
    void concurrentCallsForTheSameKey_runTheComputationOnce_andShareItsResult() throws Exception {
        Object result = new Object();
        AtomicReference<Object> leaderGot = new AtomicReference<>();
        AtomicReference<Object> waiterGot = new AtomicReference<>();

        Thread leader = ParkedThreads.start(() -> leaderGot.set(flight.run("k", () -> blockingComputation(result))));
        assertThat(leaderInside.await(5, TimeUnit.SECONDS)).as("leader inside").isTrue();
        Thread waiter = ParkedThreads.start(() -> waiterGot.set(flight.run("k", () -> {
            calls.incrementAndGet();
            return new Object();
        })));
        ParkedThreads.awaitParked(waiter);
        assertThat(flight.inFlight()).isEqualTo(1);

        releaseLeader.countDown();
        ParkedThreads.join(leader);
        ParkedThreads.join(waiter);

        assertThat(calls.get()).as("the expensive part runs once").isEqualTo(1);
        assertThat(leaderGot.get()).isSameAs(result);
        assertThat(waiterGot.get()).as("the waiter gets the leader's result").isSameAs(result);
        assertThat(flight.inFlight()).as("no entry left behind").isZero();
    }

    @Test
    void failure_reachesEveryWaiter_andTheNextCallStartsAfresh() throws Exception {
        IllegalStateException boom = new IllegalStateException("boom");
        AtomicReference<Throwable> leaderGot = new AtomicReference<>();
        AtomicReference<Throwable> waiterGot = new AtomicReference<>();

        Thread leader = ParkedThreads.start(() -> {
            try {
                flight.run("k", () -> {
                    blockingComputation(null);
                    throw boom;
                });
            } catch (Throwable t) {
                leaderGot.set(t);
            }
        });
        assertThat(leaderInside.await(5, TimeUnit.SECONDS)).as("leader inside").isTrue();
        Thread waiter = ParkedThreads.start(() -> {
            try {
                flight.run("k", Object::new);
            } catch (Throwable t) {
                waiterGot.set(t);
            }
        });
        ParkedThreads.awaitParked(waiter);

        releaseLeader.countDown();
        ParkedThreads.join(leader);
        ParkedThreads.join(waiter);

        assertThat(leaderGot.get()).isSameAs(boom);
        assertThat(waiterGot.get()).as("the waiter gets the original exception, not a CompletionException")
                .isSameAs(boom);
        assertThat(flight.inFlight()).as("no entry left behind after a failure").isZero();

        Object retried = new Object();
        assertThat(flight.run("k", () -> retried)).as("a later call computes again").isSameAs(retried);
    }

    @Test
    void error_reachesTheWaiterUnwrapped_soNobodyHangs() throws Exception {
        AssertionError fatal = new AssertionError("fatal");
        AtomicReference<Throwable> waiterGot = new AtomicReference<>();

        Thread leader = ParkedThreads.start(() -> {
            try {
                flight.run("k", () -> {
                    blockingComputation(null);
                    throw fatal;
                });
            } catch (AssertionError expected) {
                // the leader sees it as well; asserted through the waiter
            }
        });
        assertThat(leaderInside.await(5, TimeUnit.SECONDS)).as("leader inside").isTrue();
        Thread waiter = ParkedThreads.start(() -> {
            try {
                flight.run("k", Object::new);
            } catch (Throwable t) {
                waiterGot.set(t);
            }
        });
        ParkedThreads.awaitParked(waiter);

        releaseLeader.countDown();
        ParkedThreads.join(leader);
        ParkedThreads.join(waiter);

        assertThat(waiterGot.get()).isSameAs(fatal);
        assertThat(flight.inFlight()).isZero();
    }

    /**
     * A cancelled run interrupts the leader. That failure says nothing about the work, so the
     * waiter must not inherit it (with a monitor it would have run the computation itself): it
     * tries again, becomes the new leader and gets its own result.
     */
    @Test
    void interruptedLeader_waiterTriesAgain_andGetsItsOwnResult() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        Object waiterResult = new Object();
        AtomicReference<Throwable> leaderGot = new AtomicReference<>();
        AtomicBoolean leaderStillInterrupted = new AtomicBoolean();
        AtomicReference<Object> waiterGot = new AtomicReference<>();

        Thread leader = ParkedThreads.start(() -> {
            try {
                flight.run("k", () -> {
                    calls.incrementAndGet();
                    leaderInside.countDown();
                    try {
                        never.await();
                        return null;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("cancelled", e);
                    }
                });
            } catch (Throwable t) {
                leaderGot.set(t);
                leaderStillInterrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        assertThat(leaderInside.await(5, TimeUnit.SECONDS)).as("leader inside").isTrue();
        Thread waiter = ParkedThreads.start(() -> waiterGot.set(flight.run("k", () -> {
            calls.incrementAndGet();
            return waiterResult;
        })));
        ParkedThreads.awaitParked(waiter);

        leader.interrupt();
        ParkedThreads.join(leader);
        ParkedThreads.join(waiter);

        assertThat(leaderGot.get()).isInstanceOf(IllegalStateException.class).hasMessage("cancelled");
        assertThat(leaderStillInterrupted.get()).as("the leader keeps its interrupt flag").isTrue();
        assertThat(waiterGot.get()).as("the waiter runs its own computation").isSameAs(waiterResult);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(flight.inFlight()).isZero();
    }

    /** Same, when only the interrupt flag tells: the failure itself carries no interrupt cause. */
    @Test
    void leaderFailingWithItsInterruptFlagSet_letsTheWaiterTryAgain() throws Exception {
        AtomicReference<Object> waiterGot = new AtomicReference<>();

        Thread leader = ParkedThreads.start(() -> {
            try {
                flight.run("k", () -> {
                    blockingComputation(null);
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("aborted");
                });
            } catch (IllegalStateException expected) {
                // the leader's own failure; the waiter is what this test is about
            }
        });
        assertThat(leaderInside.await(5, TimeUnit.SECONDS)).as("leader inside").isTrue();
        Thread waiter = ParkedThreads.start(() -> waiterGot.set(flight.run("k", () -> "own")));
        ParkedThreads.awaitParked(waiter);

        releaseLeader.countDown();
        ParkedThreads.join(leader);
        ParkedThreads.join(waiter);

        assertThat(waiterGot.get()).isEqualTo("own");
        assertThat(flight.inFlight()).isZero();
    }

    @Test
    void failedByInterrupt_looksForInterruptsInTheCauseChain_butNotForTimeouts() {
        assertThat(SingleFlight.failedByInterrupt(new RuntimeException(new InterruptedException()))).isTrue();
        assertThat(SingleFlight.failedByInterrupt(new UncheckedIOException(new ClosedByInterruptException()))).isTrue();
        assertThat(SingleFlight.failedByInterrupt(new UncheckedIOException(new InterruptedIOException()))).isTrue();
        assertThat(SingleFlight.failedByInterrupt(new UncheckedIOException(new SocketTimeoutException())))
                .as("a read timeout is shared like any other transient failure").isFalse();
        assertThat(SingleFlight.failedByInterrupt(new IllegalStateException("docker down"))).isFalse();

        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(SingleFlight.failedByInterrupt(a)).as("a cyclic cause chain terminates").isFalse();
    }

    @Test
    void differentKeys_doNotWaitForEachOther() throws Exception {
        Thread leader = ParkedThreads.start(() -> flight.run("a", () -> blockingComputation("a")));
        assertThat(leaderInside.await(5, TimeUnit.SECONDS)).as("leader inside").isTrue();

        Object other = flight.run("b", () -> "b");

        assertThat(other).isEqualTo("b");
        assertThat(flight.inFlight()).as("only key a is still running").isEqualTo(1);
        releaseLeader.countDown();
        ParkedThreads.join(leader);
        assertThat(flight.inFlight()).isZero();
    }

    @Test
    void sequentialCalls_doNotCache() {
        assertThat(flight.run("k", () -> calls.incrementAndGet())).isEqualTo(1);
        assertThat(flight.run("k", () -> calls.incrementAndGet())).isEqualTo(2);
        assertThat(flight.inFlight()).isZero();
    }

    @Test
    void nullResult_isAllowed() {
        assertThat(flight.run("k", () -> null)).isNull();
        assertThat(flight.inFlight()).isZero();
    }

    @Test
    void nullKey_isRejected() {
        assertThatThrownBy(() -> flight.run(null, Object::new)).isInstanceOf(NullPointerException.class);
    }
}
