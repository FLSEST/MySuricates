package com.automation.service.todo;

import com.automation.config.TodoHygieneConfig;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.Todo;
import com.automation.repository.TodoRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoHygieneServiceTest {

    @Mock private TodoRepository todoRepository;
    @Mock private ActivityLogService activityLogService;
    @Mock private TodoHygieneConfig config;

    @InjectMocks private TodoHygieneService service;

    @Test
    void archiveStaleAutoTodos_disabled_isNoOp() {
        // config.isEnabled() defaults to false
        service.archiveStaleAutoTodos();

        verifyNoInteractions(todoRepository);
        verifyNoInteractions(activityLogService);
    }

    @Test
    void archiveStaleAutoTodos_enabledWithStaleTodos_archivesAndLogs() {
        when(config.isEnabled()).thenReturn(true);
        when(config.getStaleDays()).thenReturn(30);
        when(config.getMaxPerRun()).thenReturn(200);
        when(todoRepository.findStaleAutoTodoIds(any(), any())).thenReturn(List.of("x", "y"));
        when(todoRepository.bulkSetStatus(eq(List.of("x", "y")), eq(Todo.Status.ARCHIVED), any())).thenReturn(2);

        service.archiveStaleAutoTodos();

        verify(todoRepository).bulkSetStatus(eq(List.of("x", "y")), eq(Todo.Status.ARCHIVED), any());
        verify(activityLogService).info(eq(LogCategory.SYSTEM), contains("archiviert"), eq("TodoHygieneService"));
    }

    @Test
    void archiveStaleAutoTodos_enabledButNothingStale_doesNotArchive() {
        when(config.isEnabled()).thenReturn(true);
        when(config.getStaleDays()).thenReturn(30);
        when(config.getMaxPerRun()).thenReturn(200);
        when(todoRepository.findStaleAutoTodoIds(any(), any())).thenReturn(List.of());

        service.archiveStaleAutoTodos();

        verify(todoRepository, never()).bulkSetStatus(any(), any(), any());
        verifyNoInteractions(activityLogService);
    }

    @Test
    void archiveStaleNow_withProject_usesProjectScopedQuery_ignoringEnabledFlag() {
        // enabled flag NOT stubbed (false) — user-triggered archival must work regardless
        when(config.getStaleDays()).thenReturn(30);
        when(config.getMaxPerRun()).thenReturn(200);
        when(todoRepository.findStaleAutoTodoIdsForProject(eq("p-1"), any(), any())).thenReturn(List.of("a"));
        when(todoRepository.bulkSetStatus(eq(List.of("a")), eq(Todo.Status.ARCHIVED), any())).thenReturn(1);

        int archived = service.archiveStaleNow("p-1");

        org.assertj.core.api.Assertions.assertThat(archived).isEqualTo(1);
        verify(todoRepository).findStaleAutoTodoIdsForProject(eq("p-1"), any(), any());
        verify(todoRepository, never()).findStaleAutoTodoIds(any(), any());
    }
}
