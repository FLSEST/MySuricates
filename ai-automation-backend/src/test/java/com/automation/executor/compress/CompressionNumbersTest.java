package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompressionNumbersTest {

    @Test
    void fmtUsesStableGermanThousandsSeparators() {
        assertThat(CompressionNumbers.fmt(740_808)).isEqualTo("740.808");
        assertThat(CompressionNumbers.fmt(-4_102)).isEqualTo("-4.102");
    }

    @Test
    void signedCharsDistinguishesGrowthShrinkageAndNoChange() {
        assertThat(CompressionNumbers.signedChars(1_234)).isEqualTo("+1.234 Zeichen");
        assertThat(CompressionNumbers.signedChars(-1_234)).isEqualTo("−1.234 Zeichen");
        assertThat(CompressionNumbers.signedChars(0)).isEqualTo("−0 Zeichen");
    }

    @Test
    void removalLabelsUseTheTypographicMinusAndCorrectUnit() {
        assertThat(CompressionNumbers.chars(12)).isEqualTo("−12 Zeichen");
        assertThat(CompressionNumbers.lines(2_000)).isEqualTo("−2.000 Zeilen");
    }
}
