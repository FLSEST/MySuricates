package com.automation.service.health;

import com.automation.entity.Todo;
import com.automation.repository.TodoRepository;
import com.automation.service.staging.StagingService;
import com.automation.service.staging.TestingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HealthAutoTodoServiceTest {

    @Mock private StagingService stagingService;
    @Mock private TestingService testingService;
    @Mock private TodoRepository todoRepository;

    private HealthAutoTodoService service;

    @BeforeEach
    void setUp() {
        service = new HealthAutoTodoService(stagingService, testingService, todoRepository);
    }

    @Test
    void checkInstanceHealth_stableGoesDown_createsTodo() {
        // Initially healthy (default), now unhealthy
        when(stagingService.isHealthy()).thenReturn(false);
        when(testingService.isHealthy()).thenReturn(true);
        when(todoRepository.findOpenByReferenceTypeAndId("health_check", "stable")).thenReturn(Optional.empty());

        service.checkInstanceHealth();

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).contains("Stable");
        assertThat(captor.getValue().getPriority()).isEqualTo(Todo.Priority.HIGH);
    }

    @Test
    void checkInstanceHealth_stableRecovers_closesTodo() {
        // First call: stable goes down
        when(stagingService.isHealthy()).thenReturn(false);
        when(testingService.isHealthy()).thenReturn(true);
        when(todoRepository.findOpenByReferenceTypeAndId("health_check", "stable")).thenReturn(Optional.empty());
        service.checkInstanceHealth();

        // Second call: stable recovers
        Todo openTodo = Todo.builder().title("[Health] Stable").status(Todo.Status.OPEN).build();
        when(stagingService.isHealthy()).thenReturn(true);
        when(todoRepository.findOpenByReferenceTypeAndId("health_check", "stable")).thenReturn(Optional.of(openTodo));
        service.checkInstanceHealth();

        assertThat(openTodo.getStatus()).isEqualTo(Todo.Status.DONE);
        verify(todoRepository, atLeast(2)).save(any());
    }

    @Test
    void checkInstanceHealth_stableAlreadyDown_noNewTodo() {
        // Stable already down — existing open todo
        when(stagingService.isHealthy()).thenReturn(false);
        when(testingService.isHealthy()).thenReturn(true);
        Todo existing = Todo.builder().title("[Health] Stable").build();
        when(todoRepository.findOpenByReferenceTypeAndId("health_check", "stable")).thenReturn(Optional.of(existing));

        service.checkInstanceHealth();
        // Should not save a new todo (existing found)
        verify(todoRepository, never()).save(any());
    }

    @Test
    void checkInstanceHealth_testGoesDown_createsTodo() {
        when(stagingService.isHealthy()).thenReturn(true);
        when(testingService.isHealthy()).thenReturn(false);
        when(todoRepository.findOpenByReferenceTypeAndId("health_check", "test")).thenReturn(Optional.empty());

        service.checkInstanceHealth();

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).contains("Test");
    }

    @Test
    void checkInstanceHealth_bothHealthy_noAction() {
        when(stagingService.isHealthy()).thenReturn(true);
        when(testingService.isHealthy()).thenReturn(true);

        service.checkInstanceHealth();

        verify(todoRepository, never()).save(any());
        verify(todoRepository, never()).findOpenByReferenceTypeAndId(any(), any());
    }

    @Test
    void checkInstanceHealth_staysDown_noAction() {
        // First call: goes down
        when(stagingService.isHealthy()).thenReturn(false);
        when(testingService.isHealthy()).thenReturn(true);
        when(todoRepository.findOpenByReferenceTypeAndId("health_check", "stable")).thenReturn(Optional.empty());
        service.checkInstanceHealth();
        reset(todoRepository);

        // Second call: still down — no transition, no action
        when(stagingService.isHealthy()).thenReturn(false);
        when(testingService.isHealthy()).thenReturn(true);
        service.checkInstanceHealth();

        verify(todoRepository, never()).save(any());
    }
}
