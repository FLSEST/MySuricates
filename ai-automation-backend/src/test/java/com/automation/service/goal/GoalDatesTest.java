package com.automation.service.goal;

import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedArgumentException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoalDatesTest {

    @Test
    void start_ofAPlainDateIsTheBeginningOfThatDayInUtc() {
        assertThat(GoalDates.start("2026-10-15")).isEqualTo(Instant.parse("2026-10-15T00:00:00Z"));
        assertThat(GoalDates.start(" 2026-10-15 ")).isEqualTo(Instant.parse("2026-10-15T00:00:00Z"));
    }

    @Test
    void end_ofAPlainDateIsTheLastSecondOfThatDayInUtc() {
        assertThat(GoalDates.end("2026-10-15")).isEqualTo(Instant.parse("2026-10-15T23:59:59Z"));
    }

    @Test
    void anIsoTimestampIsTakenAsSentForStartAndEnd() {
        Instant sent = Instant.parse("2026-10-15T08:30:00Z");

        assertThat(GoalDates.start("2026-10-15T08:30:00Z")).isEqualTo(sent);
        assertThat(GoalDates.end("2026-10-15T08:30:00Z")).isEqualTo(sent);
    }

    @Test
    void emptyMeansNoDate() {
        assertThat(GoalDates.start(null)).isNull();
        assertThat(GoalDates.start("")).isNull();
        assertThat(GoalDates.end("   ")).isNull();
    }

    @Test
    void garbageIsALocalizedInvalidDateWithTheTrimmedValue() {
        assertThatThrownBy(() -> GoalDates.end(" morgen "))
                .isInstanceOfSatisfying(LocalizedArgumentException.class, e -> {
                    assertThat(e.errorMessage()).isEqualTo(ErrorMessage.INVALID_DATE);
                    assertThat(e.params()).isEqualTo(Map.of("value", "morgen"));
                })
                .hasMessage("Invalid date: morgen");
        assertThatThrownBy(() -> GoalDates.start("2026-13-45")).isInstanceOf(LocalizedArgumentException.class);
        assertThatThrownBy(() -> GoalDates.start("2026-10-15 08:30")).isInstanceOf(LocalizedArgumentException.class);
    }

    @Test
    void title_isTrimmedAndAllowedUpToTheLimit() {
        assertThat(GoalDates.title("  Beta  ", 10, "Paket")).isEqualTo("Beta");
        assertThat(GoalDates.title("x".repeat(10), 10, "Paket")).hasSize(10);
    }

    @Test
    void title_rejectsEmptyAndTooLongTitles() {
        assertThatThrownBy(() -> GoalDates.title(null, 10, "Ein Meilenstein"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Ein Meilenstein braucht einen Titel");
        assertThatThrownBy(() -> GoalDates.title("   ", 10, "Ein Paket"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Ein Paket braucht einen Titel");
        assertThatThrownBy(() -> GoalDates.title("x".repeat(11), 10, "Ein Paket"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Der Titel ist laenger als 10 Zeichen");
    }
}
