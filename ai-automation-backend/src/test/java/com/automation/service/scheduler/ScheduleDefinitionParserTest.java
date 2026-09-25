package com.automation.service.scheduler;

import com.automation.entity.ScheduledWorkflow.ConflictBehavior;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.ScheduledWorkflow.TargetType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

import static org.assertj.core.api.Assertions.*;

class ScheduleDefinitionParserTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-26T12:00:00Z");
    private final ScheduleDefinitionParser parser =
            new ScheduleDefinitionParser(Clock.fixed(FIXED_NOW, ZoneId.of("UTC")));

    // --- parseConflictBehavior (TODO ade3fef8: editable after creation) ---

    @Test
    void parseConflictBehavior_acceptsBothValuesCaseInsensitive() {
        assertThat(parser.parseConflictBehavior("SKIP")).isEqualTo(ConflictBehavior.SKIP);
        assertThat(parser.parseConflictBehavior(" overlap ")).isEqualTo(ConflictBehavior.OVERLAP);
    }

    /** a9bf13d5: the message names the field and the allowed values (EnumParameter). */
    @Test
    void parseConflictBehavior_unknownValue_throws() {
        assertThatThrownBy(() -> parser.parseConflictBehavior("QUEUE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("conflictBehavior muss SKIP oder OVERLAP sein, nicht 'QUEUE'");
    }

    // --- parseDelay (moved from SchedulerServiceTest) ---

    @Test
    void parseDelay_seconds_parsesCorrectly() {
        assertThat(ScheduleDefinitionParser.parseDelay("30s")).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void parseDelay_minutes_parsesCorrectly() {
        assertThat(ScheduleDefinitionParser.parseDelay("10m")).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void parseDelay_hours_parsesCorrectly() {
        assertThat(ScheduleDefinitionParser.parseDelay("2h")).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void parseDelay_days_parsesCorrectly() {
        assertThat(ScheduleDefinitionParser.parseDelay("1d")).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void parseDelay_unknownSuffix_throws() {
        assertThatThrownBy(() -> ScheduleDefinitionParser.parseDelay("5w"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid delay expression");
    }

    @Test
    void parseDelay_nonNumericValue_throws() {
        assertThatThrownBy(() -> ScheduleDefinitionParser.parseDelay("abch"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid delay expression");
    }

    @Test
    void parseDelay_emptyString_throws() {
        assertThatThrownBy(() -> ScheduleDefinitionParser.parseDelay(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- target / schedule type ---

    @Test
    void parseTargetType_nullOrBlank_defaultsToWorkflow() {
        assertThat(parser.parseTargetType(null)).isEqualTo(TargetType.WORKFLOW);
        assertThat(parser.parseTargetType("  ")).isEqualTo(TargetType.WORKFLOW);
    }

    @Test
    void parseTargetType_caseInsensitive_andTrimmed() {
        assertThat(parser.parseTargetType("chain")).isEqualTo(TargetType.CHAIN);
        assertThat(parser.parseTargetType(" Workflow ")).isEqualTo(TargetType.WORKFLOW);
    }

    @Test
    void parseTargetType_invalid_throws_namingTheAllowedValues() {
        assertThatThrownBy(() -> parser.parseTargetType("robot"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("targetType muss WORKFLOW oder CHAIN sein, nicht 'robot'");
    }

    /** a9bf13d5: upper-casing with the default locale turned "chain" into "CHAİN" under tr-TR. */
    @Test
    void parseTargetType_doesNotDependOnTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(parser.parseTargetType("chain")).isEqualTo(TargetType.CHAIN);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void parseScheduleType_nullOrBlank_defaultsToCron() {
        assertThat(parser.parseScheduleType(null)).isEqualTo(ScheduleType.CRON);
        assertThat(parser.parseScheduleType("")).isEqualTo(ScheduleType.CRON);
    }

    @Test
    void parseScheduleType_caseInsensitive_andInvalidThrows() {
        assertThat(parser.parseScheduleType("once")).isEqualTo(ScheduleType.ONCE);
        assertThat(parser.parseScheduleType(" delayed ")).isEqualTo(ScheduleType.DELAYED);
        assertThatThrownBy(() -> parser.parseScheduleType("weekly"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scheduleType muss CRON, ONCE oder DELAYED sein, nicht 'weekly'");
    }

    @Test
    void calculateNextRun_usesClockAndSystemZone() {
        Instant next = parser.calculateNextRun("0 0 13 * * *");

        assertThat(next).isEqualTo(LocalDateTime.of(2026, 4, 26, 13, 0).atZone(ZoneId.systemDefault()).toInstant());
    }
}
