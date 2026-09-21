package com.automation.service.health;

import com.automation.config.StagingConfig;
import com.automation.entity.Todo;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.service.health.HealthAutoTodoService.InstanceState;
import com.automation.service.staging.DockerContainerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static com.automation.service.health.HealthAutoTodoService.InstanceState.ABSENT;
import static com.automation.service.health.HealthAutoTodoService.InstanceState.DOWN;
import static com.automation.service.health.HealthAutoTodoService.InstanceState.UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HealthAutoTodoServiceTest {

    @Mock private DockerContainerManager docker;
    @Mock private TodoRepository todoRepository;
    @Mock private ProjectRepository projectRepository;

    private final StagingConfig config = new StagingConfig();
    private HealthAutoTodoService service;

    @BeforeEach
    void setUp() {
        service = new HealthAutoTodoService(config, docker, todoRepository, projectRepository,
                ModuleRegistry.allEnabled());
        ReflectionTestUtils.setField(service, "autopilotProjectId", "test-project-uuid");
    }

    /** UP = health endpoint answers; DOWN = container there, endpoint dead; ABSENT = no container. */
    private void givenStable(InstanceState state) {
        given(config.getHealthUrl(), config.getContainerName(), state);
    }

    private void givenTest(InstanceState state) {
        given(config.getTestHealthUrl(), config.getTestContainerName(), state);
    }

    private void given(String healthUrl, String containerName, InstanceState state) {
        when(docker.isHealthy(healthUrl)).thenReturn(state == UP);
        if (state != UP) {
            when(docker.containerExists(containerName)).thenReturn(state == DOWN);
        }
    }

    /** Runs one tick and forgets its repository calls, so the next tick is verified on its own. */
    private void tick() {
        service.checkInstanceHealth();
        clearInvocations(todoRepository, projectRepository);
    }

    private Todo openHealthTodo(String label) {
        return Todo.builder().title("[Health] " + label).status(Todo.Status.OPEN).build();
    }

    @Test
    void checkInstanceHealth_stagingModuleDisabled_probesNothingAndCreatesNoTodo() {
        HealthAutoTodoService coreOnly = new HealthAutoTodoService(config, docker, todoRepository,
                projectRepository, ModuleRegistry.of(java.util.Map.of(), java.util.Map.of()));

        coreOnly.checkInstanceHealth();

        verifyNoInteractions(docker, todoRepository);
    }

    // ── What the installation has ──────────────────────────────────────────

    @Test
    void checkInstanceHealth_singleInstanceInstall_createsNoTodo() {
        // Neither Stable nor Test was ever set up: no containers, nothing can have gone down
        givenStable(ABSENT);
        givenTest(ABSENT);

        service.checkInstanceHealth();
        service.checkInstanceHealth();

        verify(todoRepository, never()).save(any());
    }

    @Test
    void checkInstanceHealth_leftoverTodoAndNoContainer_closesIt() {
        // An old "unreachable" todo, but the instance is not set up (any more)
        Todo stale = openHealthTodo("Stable");
        givenStable(ABSENT);
        givenTest(UP);
        when(todoRepository.findOpenByReferenceTypeAndId("health_check", "stable")).thenReturn(Optional.of(stale));

        service.checkInstanceHealth();

        assertThat(stale.getStatus()).isEqualTo(Todo.Status.DONE);
        verify(todoRepository).save(stale);
    }

    @Test
    void checkInstanceHealth_healthyOnFirstTick_closesTodoLeftFromBeforeRestart() {
        Todo stale = openHealthTodo("Stable");
        givenStable(UP);
        givenTest(UP);
        when(todoRepository.findOpenByReferenceTypeAndId("health_check", "stable")).thenReturn(Optional.of(stale));

        service.checkInstanceHealth();

        assertThat(stale.getStatus()).isEqualTo(Todo.Status.DONE);
        verify(todoRepository).save(stale);
    }

    @Test
    void checkInstanceHealth_healthy_neverAsksDocker() {
        givenStable(UP);
        givenTest(UP);

        service.checkInstanceHealth();

        verify(docker, never()).containerExists(any());
    }

    // ── Outages ─────────────────────────────────────────────────────────────

    @Test
    void checkInstanceHealth_stableGoesDown_createsTodo() {
        givenStable(UP);
        givenTest(UP);
        tick();

        givenStable(DOWN);
        when(projectRepository.existsById(any())).thenReturn(true);
        service.checkInstanceHealth();

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).contains("Stable");
        assertThat(captor.getValue().getPriority()).isEqualTo(Todo.Priority.HIGH);
        assertThat(captor.getValue().getProjectId()).isEqualTo("test-project-uuid");
    }

    @Test
    void checkInstanceHealth_stableGoesDown_projectMissing_createsTodoWithoutProject() {
        givenStable(UP);
        givenTest(UP);
        tick();

        givenStable(DOWN);
        when(projectRepository.existsById(any())).thenReturn(false);
        service.checkInstanceHealth();

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).contains("Stable");
        assertThat(captor.getValue().getProjectId()).isNull();
    }

    @Test
    void checkInstanceHealth_testGoesDown_createsTodo() {
        givenStable(UP);
        givenTest(UP);
        tick();

        givenTest(DOWN);
        when(projectRepository.existsById(any())).thenReturn(true);
        service.checkInstanceHealth();

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).contains("Test");
    }

    @Test
    void checkInstanceHealth_goesDownWithTodoAlreadyOpen_noSecondTodo() {
        givenStable(UP);
        givenTest(UP);
        tick();

        givenStable(DOWN);
        when(todoRepository.findOpenByReferenceTypeAndId("health_check", "stable"))
                .thenReturn(Optional.of(openHealthTodo("Stable")));
        service.checkInstanceHealth();

        verify(todoRepository, never()).save(any());
    }

    @Test
    void checkInstanceHealth_staysDown_noAction() {
        givenStable(UP);
        givenTest(UP);
        tick();
        givenStable(DOWN);
        when(projectRepository.existsById(any())).thenReturn(true);
        tick();

        service.checkInstanceHealth();

        verifyNoInteractions(todoRepository);
    }

    @Test
    void checkInstanceHealth_stableRecovers_closesTodo() {
        givenStable(UP);
        givenTest(UP);
        tick();
        givenStable(DOWN);
        when(projectRepository.existsById(any())).thenReturn(true);
        tick();

        Todo openTodo = openHealthTodo("Stable");
        givenStable(UP);
        when(todoRepository.findOpenByReferenceTypeAndId("health_check", "stable")).thenReturn(Optional.of(openTodo));
        service.checkInstanceHealth();

        assertThat(openTodo.getStatus()).isEqualTo(Todo.Status.DONE);
        verify(todoRepository).save(openTodo);
    }

    // ── Not outages ─────────────────────────────────────────────────────────

    @Test
    void checkInstanceHealth_deliberatelyStopped_createsNoTodo() {
        // staging.sh stop / promote run "docker compose down": the container disappears
        givenStable(UP);
        givenTest(UP);
        tick();

        givenStable(ABSENT);
        service.checkInstanceHealth();

        verify(todoRepository, never()).save(any());
    }

    @Test
    void checkInstanceHealth_starting_createsNoTodo() {
        // staging.sh start: the container exists before the health endpoint answers
        givenStable(ABSENT);
        givenTest(UP);
        tick();

        givenStable(DOWN);
        service.checkInstanceHealth();

        verifyNoInteractions(todoRepository);
    }

    @Test
    void checkInstanceHealth_downOnFirstLook_createsNoTodoAndKeepsOpenOne() {
        // e.g. after a reboot: containers exited, restart policy "no" — not seen going down
        givenStable(DOWN);
        givenTest(UP);

        service.checkInstanceHealth();

        verify(todoRepository, never()).save(any());
        verify(todoRepository, never()).findOpenByReferenceTypeAndId("health_check", "stable");
    }

    @Test
    void checkInstanceHealth_bothHealthy_noActionAfterFirstTick() {
        givenStable(UP);
        givenTest(UP);
        tick();

        service.checkInstanceHealth();

        verifyNoInteractions(todoRepository);
    }

    @Test
    void checkInstanceHealth_saveFails_retriesTransitionOnNextTick() {
        givenStable(UP);
        givenTest(UP);
        tick();

        // Stable goes down, but save throws
        givenStable(DOWN);
        when(projectRepository.existsById(any())).thenReturn(true);
        when(todoRepository.save(any())).thenThrow(new RuntimeException("DB locked"));
        service.checkInstanceHealth();
        verify(todoRepository).save(any());

        // Same state on the next tick — the transition is re-detected because the
        // state was NOT updated after the failure
        reset(todoRepository);
        when(todoRepository.findOpenByReferenceTypeAndId("health_check", "stable")).thenReturn(Optional.empty());
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.checkInstanceHealth();

        verify(todoRepository).save(any());
    }
}
