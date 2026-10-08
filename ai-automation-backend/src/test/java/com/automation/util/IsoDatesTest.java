package com.automation.util;

import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedArgumentException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IsoDatesTest {

    @Test
    void aPlainDateIsTheBeginningOrTheLastSecondOfThatDayInUtc() {
        assertThat(IsoDates.startOfDay(" 2026-10-15 ")).isEqualTo(Instant.parse("2026-10-15T00:00:00Z"));
        assertThat(IsoDates.endOfDay("2026-10-15")).isEqualTo(Instant.parse("2026-10-15T23:59:59Z"));
    }

    @Test
    void anIsoTimestampIsTakenAsSent_withOrWithoutFraction() {
        assertThat(IsoDates.endOfDay("2026-10-15T21:59:59.000Z")).isEqualTo(Instant.parse("2026-10-15T21:59:59Z"));
        assertThat(IsoDates.startOfDay("2026-10-15T08:30:00Z")).isEqualTo(Instant.parse("2026-10-15T08:30:00Z"));
    }

    @Test
    void emptyMeansNoDate() {
        assertThat(IsoDates.startOfDay(null)).isNull();
        assertThat(IsoDates.endOfDay("  ")).isNull();
    }

    @Test
    void anythingElseIsALocalizedInvalidDateWithTheTrimmedValue() {
        for (String value : new String[] {"15.10.2026", "2026-13-45", "2026-10-15 08:30", "tomorrow"}) {
            assertThatThrownBy(() -> IsoDates.endOfDay(" " + value + " "))
                    .as(value)
                    .isInstanceOfSatisfying(LocalizedArgumentException.class, e -> {
                        assertThat(e.errorMessage()).isEqualTo(ErrorMessage.INVALID_DATE);
                        assertThat(e.params()).isEqualTo(Map.of("value", value));
                    });
        }
    }
}
