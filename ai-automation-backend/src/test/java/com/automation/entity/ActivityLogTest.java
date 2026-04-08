package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivityLogTest {

    // --- Builder defaults ---

    @Test
    void should_defaultMetadataToEmptyMap_when_usingBuilderDefaults() {
        ActivityLog log = ActivityLog.builder()
                .timestamp(Instant.now())
                .level(ActivityLog.LogLevel.INFO)
                .category(ActivityLog.LogCategory.SYSTEM)
                .message("test")
                .build();

        assertThat(log.getMetadata()).isNotNull().isEmpty();
    }

    // --- Setter / field access ---

    @Test
    void should_storeTimestamp_when_setViaSetter() {
        ActivityLog log = new ActivityLog();
        Instant ts = Instant.parse("2024-06-15T10:00:00Z");

        log.setTimestamp(ts);

        assertThat(log.getTimestamp()).isEqualTo(ts);
    }

    @Test
    void should_storeLevel_when_setViaSetter() {
        ActivityLog log = new ActivityLog();

        log.setLevel(ActivityLog.LogLevel.WARN);

        assertThat(log.getLevel()).isEqualTo(ActivityLog.LogLevel.WARN);
    }

    @Test
    void should_storeCategory_when_setViaSetter() {
        ActivityLog log = new ActivityLog();

        log.setCategory(ActivityLog.LogCategory.EXECUTION);

        assertThat(log.getCategory()).isEqualTo(ActivityLog.LogCategory.EXECUTION);
    }

    @Test
    void should_storeMessage_when_setViaSetter() {
        ActivityLog log = new ActivityLog();

        log.setMessage("Something happened");

        assertThat(log.getMessage()).isEqualTo("Something happened");
    }

    @Test
    void should_storeSource_when_setViaSetter() {
        ActivityLog log = new ActivityLog();

        log.setSource("WorkflowExecutor");

        assertThat(log.getSource()).isEqualTo("WorkflowExecutor");
    }

    @Test
    void should_storeReferenceId_when_setViaSetter() {
        ActivityLog log = new ActivityLog();

        log.setReferenceId("ref-123");

        assertThat(log.getReferenceId()).isEqualTo("ref-123");
    }

    @Test
    void should_storeWorkflowId_when_setViaSetter() {
        ActivityLog log = new ActivityLog();

        log.setWorkflowId("wf-456");

        assertThat(log.getWorkflowId()).isEqualTo("wf-456");
    }

    // --- createdAt audit field ---

    @Test
    void should_defaultCreatedAtToNull_when_newInstance() {
        ActivityLog log = new ActivityLog();

        assertThat(log.getCreatedAt()).isNull();
    }

    @Test
    void should_allowSettingCreatedAt_when_usingSetter() {
        ActivityLog log = new ActivityLog();
        Instant created = Instant.parse("2024-01-01T00:00:00Z");

        log.setCreatedAt(created);

        assertThat(log.getCreatedAt()).isEqualTo(created);
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        Instant now = Instant.now();
        Map<String, Object> metadata = new HashMap<>(Map.of("key", "value"));

        ActivityLog log = new ActivityLog(
                "log-id", now, ActivityLog.LogLevel.ERROR,
                ActivityLog.LogCategory.SECURITY, "Security breach",
                "SecurityFilter", "ref-999", "wf-789",
                metadata, now
        );

        assertThat(log.getId()).isEqualTo("log-id");
        assertThat(log.getTimestamp()).isEqualTo(now);
        assertThat(log.getLevel()).isEqualTo(ActivityLog.LogLevel.ERROR);
        assertThat(log.getCategory()).isEqualTo(ActivityLog.LogCategory.SECURITY);
        assertThat(log.getMessage()).isEqualTo("Security breach");
        assertThat(log.getSource()).isEqualTo("SecurityFilter");
        assertThat(log.getReferenceId()).isEqualTo("ref-999");
        assertThat(log.getWorkflowId()).isEqualTo("wf-789");
        assertThat(log.getMetadata()).containsEntry("key", "value");
        assertThat(log.getCreatedAt()).isEqualTo(now);
    }

    // --- LogLevel enum ---

    @Test
    void should_haveAllLogLevelValues_when_checkingDeclaredOrder() {
        assertThat(ActivityLog.LogLevel.values())
                .containsExactly(
                        ActivityLog.LogLevel.DEBUG,
                        ActivityLog.LogLevel.INFO,
                        ActivityLog.LogLevel.WARN,
                        ActivityLog.LogLevel.ERROR
                );
    }

    @Test
    void should_resolveLogLevelViaValueOf_when_givenValidName() {
        assertThat(ActivityLog.LogLevel.valueOf("DEBUG")).isEqualTo(ActivityLog.LogLevel.DEBUG);
        assertThat(ActivityLog.LogLevel.valueOf("INFO")).isEqualTo(ActivityLog.LogLevel.INFO);
        assertThat(ActivityLog.LogLevel.valueOf("WARN")).isEqualTo(ActivityLog.LogLevel.WARN);
        assertThat(ActivityLog.LogLevel.valueOf("ERROR")).isEqualTo(ActivityLog.LogLevel.ERROR);
    }

    @Test
    void should_throwException_when_logLevelValueOfReceivesInvalidName() {
        assertThatThrownBy(() -> ActivityLog.LogLevel.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- LogCategory enum ---

    @Test
    void should_haveAllLogCategoryValues_when_checkingDeclaredOrder() {
        assertThat(ActivityLog.LogCategory.values())
                .containsExactly(
                        ActivityLog.LogCategory.SYSTEM,
                        ActivityLog.LogCategory.EXECUTION,
                        ActivityLog.LogCategory.SCHEDULE,
                        ActivityLog.LogCategory.WEBHOOK,
                        ActivityLog.LogCategory.SECURITY,
                        ActivityLog.LogCategory.CONTAINER
                );
    }

    @Test
    void should_resolveLogCategoryViaValueOf_when_givenValidName() {
        assertThat(ActivityLog.LogCategory.valueOf("SYSTEM")).isEqualTo(ActivityLog.LogCategory.SYSTEM);
        assertThat(ActivityLog.LogCategory.valueOf("EXECUTION")).isEqualTo(ActivityLog.LogCategory.EXECUTION);
        assertThat(ActivityLog.LogCategory.valueOf("SCHEDULE")).isEqualTo(ActivityLog.LogCategory.SCHEDULE);
        assertThat(ActivityLog.LogCategory.valueOf("WEBHOOK")).isEqualTo(ActivityLog.LogCategory.WEBHOOK);
        assertThat(ActivityLog.LogCategory.valueOf("SECURITY")).isEqualTo(ActivityLog.LogCategory.SECURITY);
        assertThat(ActivityLog.LogCategory.valueOf("CONTAINER")).isEqualTo(ActivityLog.LogCategory.CONTAINER);
    }

    @Test
    void should_throwException_when_logCategoryValueOfReceivesInvalidName() {
        assertThatThrownBy(() -> ActivityLog.LogCategory.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Metadata mutability ---

    @Test
    void should_allowAddingEntries_when_usingBuilderDefaultMetadata() {
        ActivityLog log = ActivityLog.builder()
                .timestamp(Instant.now())
                .level(ActivityLog.LogLevel.INFO)
                .category(ActivityLog.LogCategory.SYSTEM)
                .message("test")
                .build();

        log.getMetadata().put("detail", "extra info");

        assertThat(log.getMetadata()).hasSize(1).containsEntry("detail", "extra info");
    }
}
