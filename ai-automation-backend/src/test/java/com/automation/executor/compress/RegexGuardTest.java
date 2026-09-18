package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class RegexGuardTest {

    @Test
    void catastrophicPattern_hitsDeadline() {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofMillis(200));
        Pattern evil = RegexGuard.compile("^(a+)+$", 0, "Stufe test");
        String line = "a".repeat(5000) + "!";

        assertThatThrownBy(() -> guard.find(evil, line))
                .isInstanceOf(CompressionException.class).hasMessageContaining("Zeitlimit");
    }

    @Test
    void nestedQuantifier_onShortLine_hitsDeadline() {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofMillis(300));
        Pattern evil = RegexGuard.compile("^((a+)+)+$", 0, "Stufe test");
        String line = "a".repeat(40) + "!";

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(() -> guard.find(evil, line))
                .isInstanceOf(CompressionException.class).hasMessageContaining("Zeitlimit"));
    }

    @Test
    void nestedQuantifier_backtrackingNearLineEnd_hitsDeadline() {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofMillis(300));
        Pattern evil = RegexGuard.compile("^((\\w+)+)+:", 0, "Stufe test");
        String line = "w".repeat(90);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(() -> guard.find(evil, line))
                .isInstanceOf(CompressionException.class).hasMessageContaining("Zeitlimit"));
    }

    @Test
    void matches_usesTheSameDeadline() {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofMillis(300));
        Pattern evil = RegexGuard.compile("((a+)+)+", 0, "Stufe test");

        assertThat(guard.matches(Pattern.compile("a+"), "aaa")).isTrue();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(() -> guard.matches(evil, "a".repeat(40) + "!"))
                .isInstanceOf(CompressionException.class).hasMessageContaining("Zeitlimit"));
    }

    @Test
    void manyShortLines_stopAtTheDeadline_notOnlyBetweenStages() {
        Duration budget = Duration.ofMillis(200);
        RegexGuard guard = RegexGuard.startingNow(budget);
        Pattern harmless = RegexGuard.compile("^\\w+: \\d+$", 0, "Stufe test");
        long start = System.nanoTime();

        assertThatThrownBy(() -> {
            for (int i = 0; i < 5_000_000; i++) guard.find(harmless, "warn: 12345");
        }).isInstanceOf(CompressionException.class).hasMessageContaining("Zeitlimit");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(budget.multipliedBy(2));
    }

    @Test
    void stackOverflow_becomesCompressionException() {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(30));
        Pattern deep = RegexGuard.compile("^(a|b)*$", 0, "Stufe test");

        assertThatThrownBy(() -> guard.find(deep, "ab".repeat(5000)))
                .isInstanceOf(CompressionException.class)
                .hasMessage("Muster zu tief verschachtelt für Zeile mit 10.000 Zeichen");
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
        Pattern first = RegexGuard.compile("^ab+c$", 0, "Stufe test");

        assertThat(RegexGuard.compile("^ab+c$", 0, "Stufe test")).isSameAs(first);
        assertThat(RegexGuard.compile("^ab+c$", Pattern.CASE_INSENSITIVE, "Stufe test")).isNotSameAs(first);
        assertThatThrownBy(() -> RegexGuard.compile("(unvollständig", 0, "Stufe test"))
                .isInstanceOf(CompressionException.class).hasMessageContaining("ungültiger regulärer Ausdruck");
    }

    @Test
    void safeCut_movesBeforeSurrogatePair() {
        assertThat(RegexGuard.safeCut("x😀", 2)).isEqualTo(1);
        assertThat(RegexGuard.safeCut("xy", 1)).isEqualTo(1);
    }
}
