package com.automation.integration;

import com.automation.dto.response.ProjectResponse;
import com.automation.entity.Container;
import com.automation.entity.ContainerStatus;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.mapper.ProjectMapper;
import com.automation.repository.ContainerRepository;
import com.automation.repository.ProjectRepository;
import com.automation.service.project.ContainerService;
import com.automation.service.project.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Container start/stop from the UI must not hold a transaction around the Docker call
 * (TODO b3c88eeb). {@code ContainerService} takes the per-project monitor and runs a 5-15 s
 * Docker create inside it; with HikariCP pool-size 1 an enclosing transaction pins the single
 * connection for that whole time, so a workflow run that already holds the monitor blocks on
 * the pool until connection-timeout (120 s) — a lock-order inversion between monitor and
 * connection. Runs against the real transaction manager and asserts, from inside the mocked
 * container call, that no transaction is active — and that the status is still persisted.
 */
@SpringBootTest
@ActiveProfiles("integration")
class ProjectContainerLifecycleIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ContainerRepository containerRepository;
    @Autowired private ProjectMapper projectMapper;
    @MockitoBean private ContainerService containerService;

    private final List<Runnable> cleanup = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            try {
                cleanup.get(i).run();
            } catch (Exception e) {
                // Expected when the row/container is already gone; anything else is worth a line.
                System.err.println("[cleanup] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        cleanup.clear();
    }

    private Project persistedProject(ProjectStatus status) {
        long nano = System.nanoTime();
        Project project = projectRepository.save(Project.builder()
                .name("container-it-" + nano).type(ProjectType.CODE)
                .path("/tmp/container-it-" + nano).status(status).build());
        cleanup.add(() -> projectRepository.deleteById(project.getId()));
        return project;
    }

    @Test
    void startContainer_doesNotHoldTransactionDuringDockerStart() {
        Project project = persistedProject(ProjectStatus.STOPPED);
        AtomicReference<Boolean> txDuringStart = new AtomicReference<>();
        when(containerService.createAndStartContainer(any())).thenAnswer(inv -> {
            txDuringStart.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        });

        Project result = projectService.startContainer(project.getId());

        assertThat(txDuringStart.get()).isFalse();
        assertThat(result.getStatus()).isEqualTo(ProjectStatus.RUNNING);
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getStatus())
                .isEqualTo(ProjectStatus.RUNNING);
    }

    /**
     * The project handed to ContainerService is detached now, and the container row is written by
     * ContainerService in its own transaction. The write burst must reload the project by id so
     * the eagerly mapped {@code container} still reaches the mapper — OSIV is off, so a missed
     * reload would surface as a LazyInitializationException in the controller.
     */
    @Test
    void startContainer_returnedProjectCarriesContainer_andMapsOutsideTransaction() {
        Project project = persistedProject(ProjectStatus.STOPPED);
        when(containerService.createAndStartContainer(any())).thenAnswer(inv -> {
            Container container = containerRepository.save(Container.builder()
                    .project(inv.getArgument(0))
                    .dockerContainerId("docker-it-1")
                    .imageName("automation-code:latest")
                    .status(ContainerStatus.RUNNING)
                    .build());
            cleanup.add(() -> containerRepository.deleteById(container.getId()));
            return container;
        });

        Project result = projectService.startContainer(project.getId());

        assertThat(result.getContainer()).isNotNull();
        assertThat(result.getContainer().getDockerContainerId()).isEqualTo("docker-it-1");
        ProjectResponse response = projectMapper.toResponse(result);
        assertThat(response.container()).isNotNull();
        assertThat(response.status()).isEqualTo(ProjectStatus.RUNNING);
    }

    @Test
    void startContainer_dockerFails_persistsErrorStatus() {
        Project project = persistedProject(ProjectStatus.STOPPED);
        doThrow(new IllegalStateException("Docker not available"))
                .when(containerService).createAndStartContainer(any());

        Project result = projectService.startContainer(project.getId());

        assertThat(result.getStatus()).isEqualTo(ProjectStatus.ERROR);
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getStatus())
                .isEqualTo(ProjectStatus.ERROR);
    }

    @Test
    void stopContainer_doesNotHoldTransactionDuringDockerStop() {
        Project project = persistedProject(ProjectStatus.RUNNING);
        AtomicReference<Boolean> txDuringStop = new AtomicReference<>();
        doAnswer(inv -> {
            txDuringStop.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(containerService).stopContainer(any());

        Project result = projectService.stopContainer(project.getId());

        assertThat(txDuringStop.get()).isFalse();
        assertThat(result.getStatus()).isEqualTo(ProjectStatus.STOPPED);
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getStatus())
                .isEqualTo(ProjectStatus.STOPPED);
    }

    /**
     * Deleting (= archiving) a running project must not hold a transaction around the Docker
     * stop either (TODO 0db2b2c9) — same lock-order inversion as start/stop.
     */
    @Test
    void delete_stopsContainerOutsideTransaction() {
        Project project = persistedProject(ProjectStatus.RUNNING);
        AtomicReference<Boolean> txDuringStop = new AtomicReference<>();
        doAnswer(inv -> {
            txDuringStop.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(containerService).stopContainer(any());

        projectService.delete(project.getId());

        assertThat(txDuringStop.get()).isFalse();
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getArchivedAt()).isNotNull();
    }

    /**
     * Archive first, Docker afterwards: a failing Docker stop must not roll the archive back.
     * A stale container is reaped by DockerOrphanReconciler / idle cleanup, whereas a project
     * the user already deleted reappearing un-archived would be the worse outcome.
     */
    @Test
    void delete_dockerStopFails_projectStaysArchived() {
        Project project = persistedProject(ProjectStatus.RUNNING);
        doThrow(new IllegalStateException("Docker not available"))
                .when(containerService).stopContainer(any());

        projectService.delete(project.getId());

        assertThat(projectRepository.findById(project.getId()).orElseThrow().getArchivedAt()).isNotNull();
    }
}
