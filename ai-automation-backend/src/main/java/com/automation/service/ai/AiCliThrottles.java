package com.automation.service.ai;

import com.automation.concurrency.CliCallThrottle;
import com.automation.config.AiCliConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.config.HostPiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The limits for AI CLI processes on the host (TODO bd5df8d7): one {@link CliCallThrottle} per provider –
 * quotas and rate limits belong to the provider account, so one provider's load does not hold up another –
 * and one across all of them, which protects the host itself. A call takes a slot of its provider first and
 * then one of the overall limit; every caller takes them in that order, so they cannot block each other in
 * a circle.
 */
@Component
@Slf4j
public class AiCliThrottles {

    public static final String CLAUDE = "CLAUDE";
    public static final String PI = "PI";
    public static final String CODEX = "CODEX";

    private final CliCallThrottle total;
    private final Map<String, CliCallThrottle> providers = new LinkedHashMap<>();

    public AiCliThrottles(HostClaudeConfig claude, HostPiConfig pi, AiCliConfig cli) {
        providers.put(CLAUDE, new CliCallThrottle("Claude", claude.getMaxConcurrentCalls(),
                claude.getReservedForInteractive(), claude::getInterCallDelayMs));
        providers.put(PI, new CliCallThrottle("pi", pi.getMaxConcurrentCalls(),
                pi.getReservedForInteractive(), pi::getInterCallDelayMs));
        providers.put(CODEX, new CliCallThrottle("Codex", cli.getCodexMaxConcurrentCalls(),
                cli.getCodexReservedForInteractive(), cli::getCodexInterCallDelayMs));
        total = new CliCallThrottle("all AI CLIs", cli.getMaxConcurrentCalls(), cli.getReservedForInteractive(), () -> 0);
        providers.values().forEach(t -> log.info("{} CLI throttle: maxConcurrent={}, backgroundLimit={}",
                t.name(), t.maxConcurrent(), t.backgroundLimit()));
        log.info("AI CLI throttle across providers: maxConcurrent={}, backgroundLimit={}",
                total.maxConcurrent(), total.backgroundLimit());
    }

    /** Defaults of all config classes – for tests and callers that build a service by hand. */
    public static AiCliThrottles defaults() {
        return new AiCliThrottles(new HostClaudeConfig(), new HostPiConfig(), new AiCliConfig());
    }

    /**
     * Waits for a slot of {@code provider} and one of the overall limit. Close the permit once the process
     * has ended.
     *
     * @throws IOException when the thread is interrupted while waiting; nothing is held then
     */
    public Permit acquire(String provider, boolean interactive) throws IOException {
        CliCallThrottle own = provider(provider);
        try {
            own.acquire(interactive);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + own.name() + " CLI throttle permit", e);
        }
        try {
            total.acquire(interactive);
        } catch (InterruptedException e) {
            own.release(interactive);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the overall AI CLI throttle permit", e);
        }
        return new Permit(own, interactive);
    }

    /** @throws IllegalArgumentException for a provider without its own limit */
    public CliCallThrottle provider(String provider) {
        CliCallThrottle throttle = providers.get(provider);
        if (throttle == null) {
            throw new IllegalArgumentException("No CLI throttle for provider " + provider);
        }
        return throttle;
    }

    public CliCallThrottle total() {
        return total;
    }

    /** The slots of one call; {@link #close} gives them back once, in reverse order. */
    public final class Permit implements AutoCloseable {

        private final CliCallThrottle own;
        private final boolean interactive;
        private boolean closed;

        private Permit(CliCallThrottle own, boolean interactive) {
            this.own = own;
            this.interactive = interactive;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                total.release(interactive);
            } finally {
                own.release(interactive);
            }
        }
    }
}
