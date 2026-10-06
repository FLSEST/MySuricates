package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class RegexGuardTest {

    /**
     * The line must lie between two bounds, otherwise the test checks nothing anymore.
     *
     * <p>Lower bound: the effort grows quadratically (measured on 2026-09-22 with
     * {@code ^(a+)+$} — 2 500 characters 69 ms, 5 000 characters 233 ms, 9 000 characters 797 ms).
     * With the earlier 5 000 characters the search was only 17 % over the deadline; on a
     * faster machine it finished earlier, no exception, test red (CI run
     * 35779550906: 193 ms against a 200 ms deadline). 9 000 characters give roughly four times
     * the deadline and also tolerate a machine twice as fast.
     *
     * <p>Upper bound: from a line above {@link RegexGuard#MAX_MATCH_CHARS} the
     * guard cuts off the trailing {@code !} — then the pattern matches immediately, the search
     * is done in 0 ms and no deadline can ever apply.
     */
    @Test
    void catastrophicPattern_hitsDeadline() {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofMillis(200));
        Pattern evil = RegexGuard.compile("^(a+)+$", 0, "Stage test");
        String line = "a".repeat(9000) + "!";
        assertThat(line.length()).isLessThanOrEqualTo(RegexGuard.MAX_MATCH_CHARS);

        assertThatThrownBy(() -> guard.find(evil, line))
                .isInstanceOf(CompressionException.class).hasMessageContaining("Time limit");
    }

    @Test
    void nestedQuantifier_onShortLine_hitsDeadline() {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofMillis(300));
        Pattern evil = RegexGuard.compile("^((a+)+)+$", 0, "Stage test");
        String line = "a".repeat(40) + "!";

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(() -> guard.find(evil, line))
                .isInstanceOf(CompressionException.class).hasMessageContaining("Time limit"));
    }

    @Test
    void nestedQuantifier_backtrackingNearLineEnd_hitsDeadline() {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofMillis(300));
        Pattern evil = RegexGuard.compile("^((\\w+)+)+:", 0, "Stage test");
        String line = "w".repeat(90);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(() -> guard.find(evil, line))
                .isInstanceOf(CompressionException.class).hasMessageContaining("Time limit"));
    }

    @Test
    void matches_usesTheSameDeadline() {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofMillis(300));
        Pattern evil = RegexGuard.compile("((a+)+)+", 0, "Stage test");

        assertThat(guard.matches(Pattern.compile("a+"), "aaa")).isTrue();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(() -> guard.matches(evil, "a".repeat(40) + "!"))
                .isInstanceOf(CompressionException.class).hasMessageContaining("Time limit"));
    }

    @Test
    void manyShortLines_stopAtTheDeadline_notOnlyBetweenStages() {
        Duration budget = Duration.ofMillis(200);
        RegexGuard guard = RegexGuard.startingNow(budget);
        Pattern harmless = RegexGuard.compile("^\\w+: \\d+$", 0, "Stage test");
        long start = System.nanoTime();

        assertThatThrownBy(() -> {
            for (int i = 0; i < 5_000_000; i++) guard.find(harmless, "warn: 12345");
        }).isInstanceOf(CompressionException.class).hasMessageContaining("Time limit");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(budget.multipliedBy(2));
    }

    /**
     * Runs in a thread with an explicitly small stack: whether {@code ^(a|b)*$} overflows must not depend on
     * the default stack size of whoever runs the suite (-Xss, JDK, CI agent).
     */
    @Test
    void stackOverflow_becomesCompressionException() throws InterruptedException {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(30));
        Pattern deep = RegexGuard.compile("^(a|b)*$", 0, "Stage test");
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Thread smallStack = new Thread(null, () -> {
            try {
                guard.find(deep, "ab".repeat(5000));
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "regex-guard-small-stack", 256 * 1024);
        smallStack.start();
        smallStack.join(30_000);

        assertThat(smallStack.isAlive()).as("Matching ist beendet").isFalse();
        assertThat(thrown.get())
                .isInstanceOf(CompressionException.class)
                .hasMessage("Pattern nested too deeply for a line of 10,000 chars");
    }

    @Test
    void replaceAll_onlyTouchesFirstTenThousandChars() {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));
        String line = "a".repeat(12_000);

        String out = guard.replaceAll(Pattern.compile("a"), line, "b");

        assertThat(out).hasSize(12_000);
        assertThat(out).startsWith("b").endsWith("a");
    }

    /** validate() and apply() ask for the same pattern — it must be compiled once, not twice. */
    @Test
    void compile_reusesTheCompiledPattern() {
        Pattern first = RegexGuard.compile("^ab+c$", 0, "Stage test");

        assertThat(RegexGuard.compile("^ab+c$", 0, "Stage test")).isSameAs(first);
        assertThat(RegexGuard.compile("^ab+c$", Pattern.CASE_INSENSITIVE, "Stage test")).isNotSameAs(first);
        assertThatThrownBy(() -> RegexGuard.compile("(unvollständig", 0, "Stage test"))
                .isInstanceOf(CompressionException.class).hasMessageContaining("invalid regular expression");
    }

    @Test
    void safeCut_movesBeforeSurrogatePair() {
        assertThat(RegexGuard.safeCut("x😀", 2)).isEqualTo(1);
        assertThat(RegexGuard.safeCut("xy", 1)).isEqualTo(1);
    }
}
