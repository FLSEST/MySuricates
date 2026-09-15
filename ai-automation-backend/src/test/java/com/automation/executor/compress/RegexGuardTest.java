package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void replaceAll_onlyTouchesFirstTenThousandChars() {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));
        String line = "a".repeat(12_000);

        String out = guard.replaceAll(Pattern.compile("a"), line, "b");

        assertThat(out).hasSize(12_000);
        assertThat(out).startsWith("b").endsWith("a");
    }

    @Test
    void safeCut_movesBeforeSurrogatePair() {
        assertThat(RegexGuard.safeCut("x😀", 2)).isEqualTo(1);
        assertThat(RegexGuard.safeCut("xy", 1)).isEqualTo(1);
    }
}
