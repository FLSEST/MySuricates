package com.automation.service.ai;

import com.automation.config.AiCliConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.config.HostPiConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiCliThrottlesTest {

    private static final long BLOCKED_MS = 150;

    private final HostClaudeConfig claude = new HostClaudeConfig();
    private final HostPiConfig pi = new HostPiConfig();
    private final AiCliConfig cli = new AiCliConfig();

    @Test
    void startsWithTheConfiguredLimits() {
        claude.setMaxConcurrentCalls(4);
        pi.setReservedForInteractive(2);
        cli.setCodexMaxConcurrentCalls(3);
        cli.setMaxConcurrentCalls(9);
        AiCliThrottles throttles = new AiCliThrottles(claude, pi, cli);

        assertThat(throttles.provider(AiCliThrottles.CLAUDE).maxConcurrent()).isEqualTo(4);
        assertThat(throttles.provider(AiCliThrottles.PI).reservedForInteractive()).isEqualTo(2);
        assertThat(throttles.provider(AiCliThrottles.CODEX).maxConcurrent()).isEqualTo(3);
        assertThat(throttles.total().maxConcurrent()).isEqualTo(9);
    }

    @Test
    void defaultsAreFivePerProviderAndEightOverall() {
        AiCliThrottles throttles = AiCliThrottles.defaults();

        for (String provider : new String[] {AiCliThrottles.CLAUDE, AiCliThrottles.PI, AiCliThrottles.CODEX}) {
            assertThat(throttles.provider(provider).maxConcurrent()).isEqualTo(5);
            assertThat(throttles.provider(provider).reservedForInteractive()).isEqualTo(1);
        }
        assertThat(throttles.total().maxConcurrent()).isEqualTo(8);
        assertThat(throttles.total().reservedForInteractive()).isEqualTo(1);
    }

    @Test
    void theOverallLimitCountsTheCallsOfAllProviders() throws Exception {
        cli.setMaxConcurrentCalls(2);
        cli.setReservedForInteractive(0);
        AiCliThrottles throttles = new AiCliThrottles(claude, pi, cli);
        AiCliThrottles.Permit claudeCall = throttles.acquire(AiCliThrottles.CLAUDE, false);
        throttles.acquire(AiCliThrottles.CODEX, false);

        Waiter piCall = Waiter.start(() -> throttles.acquire(AiCliThrottles.PI, false));
        assertThat(piCall.passed(BLOCKED_MS)).as("pi waits although its own limit is free").isFalse();

        claudeCall.close();
        assertThat(piCall.passed(5000)).isTrue();
    }

    @Test
    void oneProvidersLoadDoesNotHoldUpAnother() throws Exception {
        claude.setMaxConcurrentCalls(1);
        claude.setReservedForInteractive(0);
        AiCliThrottles throttles = new AiCliThrottles(claude, pi, cli);
        throttles.acquire(AiCliThrottles.CLAUDE, false);

        Waiter secondClaude = Waiter.start(() -> throttles.acquire(AiCliThrottles.CLAUDE, false));
        Waiter codex = Waiter.start(() -> throttles.acquire(AiCliThrottles.CODEX, false));

        assertThat(codex.passed(5000)).isTrue();
        assertThat(secondClaude.passed(BLOCKED_MS)).isFalse();
    }

    @Test
    void anInteractiveCallUsesTheReserveOfItsProviderAndOfTheOverallLimit() throws Exception {
        claude.setMaxConcurrentCalls(2);
        claude.setReservedForInteractive(1);
        cli.setMaxConcurrentCalls(2);
        cli.setReservedForInteractive(1);
        AiCliThrottles throttles = new AiCliThrottles(claude, pi, cli);
        throttles.acquire(AiCliThrottles.CLAUDE, false);

        Waiter background = Waiter.start(() -> throttles.acquire(AiCliThrottles.CLAUDE, false));
        Waiter chat = Waiter.start(() -> throttles.acquire(AiCliThrottles.CLAUDE, true));

        assertThat(chat.passed(5000)).isTrue();
        assertThat(background.passed(BLOCKED_MS)).isFalse();
    }

    @Test
    void closingAPermitTwiceGivesBackItsSlotsOnce() throws Exception {
        claude.setMaxConcurrentCalls(1);
        claude.setReservedForInteractive(0);
        AiCliThrottles throttles = new AiCliThrottles(claude, pi, cli);

        AiCliThrottles.Permit permit = throttles.acquire(AiCliThrottles.CLAUDE, false);
        permit.close();
        permit.close();
        throttles.acquire(AiCliThrottles.CLAUDE, false);

        Waiter next = Waiter.start(() -> throttles.acquire(AiCliThrottles.CLAUDE, false));
        assertThat(next.passed(BLOCKED_MS)).as("only one slot exists").isFalse();
    }

    @Test
    void interruptedWhileWaitingForTheOverallLimitTheProviderSlotIsGivenBack() throws Exception {
        claude.setMaxConcurrentCalls(1);
        claude.setReservedForInteractive(0);
        cli.setMaxConcurrentCalls(1);
        cli.setReservedForInteractive(0);
        AiCliThrottles throttles = new AiCliThrottles(claude, pi, cli);
        AiCliThrottles.Permit codexCall = throttles.acquire(AiCliThrottles.CODEX, false);

        Waiter claudeCall = Waiter.start(() -> throttles.acquire(AiCliThrottles.CLAUDE, false));
        assertThat(claudeCall.passed(BLOCKED_MS)).isFalse();
        claudeCall.thread().interrupt();
        claudeCall.thread().join(5000);
        assertThat(claudeCall.failure().get()).isInstanceOf(IOException.class)
                .hasMessageContaining("overall AI CLI throttle");

        codexCall.close();
        Waiter retry = Waiter.start(() -> throttles.acquire(AiCliThrottles.CLAUDE, false));
        assertThat(retry.passed(5000)).as("the Claude slot was not kept").isTrue();
    }

    @Test
    void anUnknownProviderHasNoThrottle() {
        assertThatThrownBy(() -> AiCliThrottles.defaults().provider("GEMINI"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GEMINI");
    }

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
