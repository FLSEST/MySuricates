package com.automation.service.todo;

import com.automation.config.TodoGhostWatchdogConfig;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.Todo;
import com.automation.repository.TodoRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoGhostWatchdogServiceTest {

    @Mock private TodoRepository todoRepository;
    @Mock private ActivityLogService activityLogService;

    private TodoGhostWatchdogConfig config;
    private TodoGhostWatchdogService service;

    @BeforeEach
    void setUp() {
        config = new TodoGhostWatchdogConfig();
        service = new TodoGhostWatchdogService(todoRepository, activityLogService, config);
    }

    @Test
    void resetStaleInProgressTodos_staleFound_resetsToOpenAndLogs() {
        List<String> staleIds = List.of("id-1", "id-2");
        when(todoRepository.findStaleInProgressAutoTodoIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(staleIds);
        when(todoRepository.bulkSetStatus(eq(staleIds), eq(Todo.Status.OPEN), any(Instant.class)))
                .thenReturn(2);

        service.resetStaleInProgressTodos();

        verify(todoRepository).bulkSetStatus(eq(staleIds), eq(Todo.Status.OPEN), any(Instant.class));
        verify(activityLogService).warn(eq(LogCategory.SYSTEM), contains("2"), eq("TodoGhostWatchdogService"));
    }

    @Test
    void resetStaleInProgressTodos_disabled_isNoOp() {
        config.setEnabled(false);

        service.resetStaleInProgressTodos();

        verifyNoInteractions(todoRepository);
        verifyNoInteractions(activityLogService);
    }

    @Test
    void resetStaleInProgressTodos_noStale_noActionNoLog() {
        when(todoRepository.findStaleInProgressAutoTodoIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        service.resetStaleInProgressTodos();

        verify(todoRepository, never()).bulkSetStatus(any(), any(), any());
        verifyNoInteractions(activityLogService);
    }

    @Test
    void resetStaleInProgressTodos_cutoffReflectsConfiguredStaleHours() {
        config.setStaleHours(48);
        when(todoRepository.findStaleInProgressAutoTodoIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        Instant before = Instant.now();
        service.resetStaleInProgressTodos();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(todoRepository).findStaleInProgressAutoTodoIds(cutoff.capture(), any(Pageable.class));
        Instant expected = before.minus(Duration.ofHours(48));
        assertThat(cutoff.getValue()).isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
    }

    @Test
    void resetStaleInProgressTodos_batchIsCappedByMaxPerRun() {
        config.setMaxPerRun(7);
        when(todoRepository.findStaleInProgressAutoTodoIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        service.resetStaleInProgressTodos();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(todoRepository).findStaleInProgressAutoTodoIds(any(Instant.class), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(7);
    }
}
