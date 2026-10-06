package com.automation.concurrency;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CliCallThrottleTest {

    /** Long enough to tell "waits" from "got through" without slowing the suite down. */
    private static final long BLOCKED_MS = 150;

    @Test
    void backgroundCallsLeaveTheReserveForInteractiveOnes() throws Exception {
        CliCallThrottle throttle = new CliCallThrottle("test", 3, 1, () -> 0);
        throttle.acquire(false);
        throttle.acquire(false);

        Waiter third = Waiter.start(() -> throttle.acquire(false));
        assertThat(third.passed(BLOCKED_MS)).as("third background call waits").isFalse();

        throttle.acquire(true);   // the reserved slot – would hang the test if it were taken

        throttle.release(false);
        assertThat(third.passed(5000)).as("released slot goes to the waiting call").isTrue();
    }

    @Test
    void interactiveCallsWaitOnlyForTheOverallLimit() throws Exception {
        CliCallThrottle throttle = new CliCallThrottle("test", 2, 1, () -> 0);
        throttle.acquire(true);
        throttle.acquire(true);

        Waiter background = Waiter.start(() -> throttle.acquire(false));
        assertThat(background.passed(BLOCKED_MS)).isFalse();

        throttle.release(true);
        assertThat(background.passed(5000)).isTrue();
    }

    @Test
    void backgroundLimitIsNeverBelowOne() {
        CliCallThrottle throttle = new CliCallThrottle("test", 2, 5, () -> 0);

        assertThat(throttle.backgroundLimit()).isEqualTo(1);
        throttle.setReservedForInteractive(0);
        assertThat(throttle.backgroundLimit()).isEqualTo(2);
    }

    @Test
    void limitsChangeAtRuntime() throws Exception {
        CliCallThrottle throttle = new CliCallThrottle("test", 2, 1, () -> 0);
        throttle.acquire(false);

        Waiter second = Waiter.start(() -> throttle.acquire(false));
        assertThat(second.passed(BLOCKED_MS)).isFalse();

        throttle.setMaxConcurrent(4);
        assertThat(second.passed(5000)).as("a raised limit lets the waiting call through").isTrue();
        assertThat(throttle.maxConcurrent()).isEqualTo(4);
        assertThat(throttle.backgroundLimit()).isEqualTo(3);

        throttle.setReservedForInteractive(2);
        assertThat(throttle.reservedForInteractive()).isEqualTo(2);
        assertThat(throttle.backgroundLimit()).isEqualTo(2);
    }

    @Test
    void interruptedWhileWaitingForTheOverallLimitTheBackgroundPermitIsGivenBack() throws Exception {
        CliCallThrottle throttle = new CliCallThrottle("test", 2, 0, () -> 0);
        throttle.acquire(true);
        throttle.acquire(true);

        // takes its background permit, then waits for the overall limit
        Waiter waiter = Waiter.start(() -> throttle.acquire(false));
        assertThat(waiter.passed(BLOCKED_MS)).isFalse();
        waiter.thread().interrupt();
        waiter.thread().join(5000);
        assertThat(waiter.failure().get()).isInstanceOf(InterruptedException.class);

        throttle.release(true);
        throttle.release(true);
        Waiter both = Waiter.start(() -> {
            throttle.acquire(false);
            throttle.acquire(false);
        });
        assertThat(both.passed(5000)).as("both background permits are free again").isTrue();
    }

    @Test
    void keepsTheDistanceBetweenCallsAndReadsItOnEveryCall() throws Exception {
        AtomicLong delay = new AtomicLong(0);
        CliCallThrottle throttle = new CliCallThrottle("test", 5, 0, delay::get);
        throttle.acquire(false);

        delay.set(200);
        long start = System.nanoTime();
        throttle.acquire(false);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(150);
    }

    /** A call on its own thread; {@link #passed} tells whether it got through within the time. */
    private record Waiter(Thread thread, CountDownLatch done, AtomicReference<Throwable> failure) {

        interface Call {
            void run() throws Exception;
        }

        static Waiter start(Call call) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    call.run();
                    done.countDown();
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            thread.setDaemon(true);
            thread.start();
            return new Waiter(thread, done, failure);
        }

        boolean passed(long millis) throws InterruptedException {
            return done.await(millis, TimeUnit.MILLISECONDS);
        }
    }
}
