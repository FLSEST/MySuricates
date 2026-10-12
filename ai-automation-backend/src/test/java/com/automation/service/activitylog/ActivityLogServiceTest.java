package com.automation.service.activitylog;

import com.automation.entity.ActivityLog;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.repository.ActivityLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActivityLogServiceTest {

    @Mock
    private ActivityLogRepository repository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private ActivityLogService service;

    @Test
    void log_savesEntryWithCorrectFields() {
        when(repository.save(any())).thenAnswer(inv -> {
            ActivityLog entry = inv.getArgument(0);
            entry.setId("log-1");
            return entry;
        });

        ActivityLog result = service.log(LogLevel.INFO, LogCategory.EXECUTION,
                "Workflow started", "executor", "run-1", Map.of("key", "value"));

        assertThat(result.getId()).isEqualTo("log-1");
        assertThat(result.getLevel()).isEqualTo(LogLevel.INFO);
        assertThat(result.getCategory()).isEqualTo(LogCategory.EXECUTION);
        assertThat(result.getMessage()).isEqualTo("Workflow started");
        assertThat(result.getSource()).isEqualTo("executor");
        assertThat(result.getReferenceId()).isEqualTo("run-1");
        assertThat(result.getMetadata()).containsEntry("key", "value");
        assertThat(result.getTimestamp()).isNotNull();
    }

    @Test
    void log_nullMetadata_usesEmptyMap() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ActivityLog result = service.log(LogLevel.INFO, LogCategory.SYSTEM,
                "test", null, null, null);

        assertThat(result.getMetadata()).isEmpty();
    }

    @Test
    void log_broadcastsViaWebSocket() {
        when(repository.save(any())).thenAnswer(inv -> {
            ActivityLog entry = inv.getArgument(0);
            entry.setId("log-1");
            return entry;
        });

        service.log(LogLevel.WARN, LogCategory.SECURITY, "Alert", "guard", null, null);

        verify(messagingTemplate).convertAndSend(eq("/topic/activity-log"), (Object) any(Map.class));
    }

    @Test
    void log_webSocketFailure_doesNotThrow() {
        when(repository.save(any())).thenAnswer(inv -> {
            ActivityLog entry = inv.getArgument(0);
            entry.setId("log-1");
            return entry;
        });
        doThrow(new RuntimeException("WS down")).when(messagingTemplate)
                .convertAndSend(anyString(), (Object) any(Map.class));

        // Should not throw
        ActivityLog result = service.log(LogLevel.INFO, LogCategory.SYSTEM, "test", null, null, null);
        assertThat(result).isNotNull();
    }

    @Test
    void info_categoryAndMessage_setsInfoLevel() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ActivityLog result = service.info(LogCategory.EXECUTION, "Started");

        assertThat(result.getLevel()).isEqualTo(LogLevel.INFO);
        assertThat(result.getCategory()).isEqualTo(LogCategory.EXECUTION);
        assertThat(result.getMessage()).isEqualTo("Started");
        assertThat(result.getSource()).isNull();
    }

    @Test
    void info_withSource_setsSource() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ActivityLog result = service.info(LogCategory.SYSTEM, "Boot", "SystemService");

        assertThat(result.getSource()).isEqualTo("SystemService");
    }

    @Test
    void info_withReferenceId_setsReferenceId() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ActivityLog result = service.info(LogCategory.EXECUTION, "Done", "executor", "run-1");

        assertThat(result.getReferenceId()).isEqualTo("run-1");
    }

    @Test
    void warn_setsWarnLevel() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ActivityLog result = service.warn(LogCategory.CONTAINER, "Low memory", "monitor");

        assertThat(result.getLevel()).isEqualTo(LogLevel.WARN);
    }

    @Test
    void error_setsErrorLevel() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ActivityLog result = service.error(LogCategory.EXECUTION, "Crash", "executor");

        assertThat(result.getLevel()).isEqualTo(LogLevel.ERROR);
    }

    @Test
    void findAll_noFilters_returnsAllOrdered() {
        Page<ActivityLog> page = new PageImpl<>(List.of());
        when(repository.findAllByOrderByTimestampDesc(any(PageRequest.class))).thenReturn(page);

        Page<ActivityLog> result = service.findAll(0, 20, null, null);

        assertThat(result).isNotNull();
        verify(repository).findAllByOrderByTimestampDesc(PageRequest.of(0, 20));
    }

    @Test
    void findAll_filterByLevel_usesLevelQuery() {
        Page<ActivityLog> page = new PageImpl<>(List.of());
        when(repository.findByLevelOrderByTimestampDesc(eq(LogLevel.ERROR), any(PageRequest.class))).thenReturn(page);

        service.findAll(0, 20, LogLevel.ERROR, null);

        verify(repository).findByLevelOrderByTimestampDesc(eq(LogLevel.ERROR), any(PageRequest.class));
    }

    @Test
    void findAll_filterByCategory_usesCategoryQuery() {
        Page<ActivityLog> page = new PageImpl<>(List.of());
        when(repository.findByCategoryOrderByTimestampDesc(eq(LogCategory.SYSTEM), any(PageRequest.class))).thenReturn(page);

        service.findAll(0, 20, null, LogCategory.SYSTEM);

        verify(repository).findByCategoryOrderByTimestampDesc(eq(LogCategory.SYSTEM), any(PageRequest.class));
    }

    @Test
    void findAll_filterByBoth_usesLevelAndCategoryQuery() {
        Page<ActivityLog> page = new PageImpl<>(List.of());
        when(repository.findByLevelAndCategoryOrderByTimestampDesc(
                eq(LogLevel.WARN), eq(LogCategory.SCHEDULE), any(PageRequest.class))).thenReturn(page);

        service.findAll(0, 20, LogLevel.WARN, LogCategory.SCHEDULE);

        verify(repository).findByLevelAndCategoryOrderByTimestampDesc(
                eq(LogLevel.WARN), eq(LogCategory.SCHEDULE), any(PageRequest.class));
    }

    @Test
    void cleanupLogs_callsAllThreeDeleteQueries() {
        when(repository.deleteOlderThan(any(Instant.class))).thenReturn(10);
        when(repository.deleteByLevelsOlderThan(eq(List.of(LogLevel.DEBUG, LogLevel.INFO)), any(Instant.class))).thenReturn(5);
        when(repository.deleteByLevelsOlderThan(eq(List.of(LogLevel.DEBUG)), any(Instant.class))).thenReturn(2);

        service.cleanupLogs();

        verify(repository).deleteOlderThan(any(Instant.class));
        verify(repository, times(2)).deleteByLevelsOlderThan(anyList(), any(Instant.class));
    }

    @Test
    void cleanupLogs_noEntries_doesNotLog() {
        when(repository.deleteOlderThan(any(Instant.class))).thenReturn(0);
        when(repository.deleteByLevelsOlderThan(anyList(), any(Instant.class))).thenReturn(0);

        // Should complete without error
        service.cleanupLogs();
    }
}
