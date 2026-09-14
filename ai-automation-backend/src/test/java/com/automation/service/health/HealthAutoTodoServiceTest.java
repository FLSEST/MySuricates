package com.automation.service.health;

import com.automation.entity.Todo;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.service.staging.StagingService;
import com.automation.service.staging.TestingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
    @Mock private ProjectRepository projectRepository;

    private HealthAutoTodoService service;

    @BeforeEach
    void setUp() {
        service = new HealthAutoTodoService(stagingService, testingService, todoRepository, projectRepository,
                ModuleRegistry.allEnabled());
        ReflectionTestUtils.setField(service, "autopilotProjectId", "test-project-uuid");
    }

    @Test
    void checkInstanceHealth_stagingModuleDisabled_probesNothingAndCreatesNoTodo() {
        HealthAutoTodoService coreOnly = new HealthAutoTodoService(stagingService, testingService, todoRepository,
                projectRepository, ModuleRegistry.of(java.util.Map.of(), java.util.Map.of()));

        coreOnly.checkInstanceHealth();

        verifyNoInteractions(stagingService, testingService, todoRepository);
    }

    @Test
    void checkInstanceHealth_stableGoesDown_createsTodo() {
        // Initially healthy (default), now unhealthy
        when(stagingService.isHealthy()).thenReturn(false);
        when(testingService.isHealthy()).thenReturn(true);
        when(todoRepository.findOpenByReferenceTypeAndId("health_check", "stable")).thenReturn(Optional.empty());
        when(projectRepository.existsById(any())).thenReturn(true);

        service.checkInstanceHealth();

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).contains("Stable");
        assertThat(captor.getValue().getPriority()).isEqualTo(Todo.Priority.HIGH);
        assertThat(captor.getValue().getProjectId()).isNotNull();
    }

    @Test
    void checkInstanceHealth_stableGoesDown_projectMissing_createsTodoWithoutProject() {
        when(stagingService.isHealthy()).thenReturn(false);
        when(testingService.isHealthy()).thenReturn(true);
        when(todoRepository.findOpenByReferenceTypeAndId("health_check", "stable")).thenReturn(Optional.empty());
        when(projectRepository.existsById(any())).thenReturn(false);

        service.checkInstanceHealth();

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).contains("Stable");
        assertThat(captor.getValue().getProjectId()).isNull();
    }

    @Test
    void checkInstanceHealth_stableRecovers_closesTodo() {
        // First call: stable goes down
        when(stagingService.isHealthy()).thenReturn(false);
        when(testingService.isHealthy()).thenReturn(true);
        when(todoRepository.findOpenByReferenceTypeAndId("health_check", "stable")).thenReturn(Optional.empty());
        when(projectRepository.existsById(any())).thenReturn(true);
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
        when(projectRepository.existsById(any())).thenReturn(true);

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
        when(projectRepository.existsById(any())).thenReturn(true);
        service.checkInstanceHealth();
        reset(todoRepository);

        // Second call: still down — no transition, no action
        when(stagingService.isHealthy()).thenReturn(false);
        when(testingService.isHealthy()).thenReturn(true);
        service.checkInstanceHealth();

        verify(todoRepository, never()).save(any());
    }

    @Test
    void checkInstanceHealth_saveFails_retriesTransitionOnNextTick() {
        // First call: stable goes down, but save throws
        when(stagingService.isHealthy()).thenReturn(false);
        when(testingService.isHealthy()).thenReturn(true);
        when(todoRepository.findOpenByReferenceTypeAndId("health_check", "stable")).thenReturn(Optional.empty());
        when(projectRepository.existsById(any())).thenReturn(true);
        when(todoRepository.save(any())).thenThrow(new RuntimeException("DB locked"));

        service.checkInstanceHealth();

        // save was attempted but failed
        verify(todoRepository).save(any());

        // Second call: same state — transition should be re-detected because
        // state was NOT updated after the failure
        reset(todoRepository);
        when(todoRepository.findOpenByReferenceTypeAndId("health_check", "stable")).thenReturn(Optional.empty());
        when(projectRepository.existsById(any())).thenReturn(true);
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.checkInstanceHealth();

        // Should have retried the save
        verify(todoRepository).save(any());
    }
}
