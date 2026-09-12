package com.automation.service.project;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.Container;
import com.automation.entity.ContainerStatus;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.repository.ContainerRepository;
import com.automation.repository.ProjectRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Startup reconciliation of projects stuck in STARTING (TODO 3c119d47). Since the start burst
 * commits STARTING before the 5-15 s Docker create, a crash in that window leaves the project
 * STARTING forever — no UI action and no other reconciler ever clears it.
 */
@ExtendWith(MockitoExtension.class)
class ProjectStatusReconcilerTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ContainerRepository containerRepository;
    @Mock private ActivityLogService activityLogService;

    private ProjectStatusReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new ProjectStatusReconciler(projectRepository, containerRepository, activityLogService);
    }

    private static Project project(String id) {
        return Project.builder()
                .id(id)
                .name("Project " + id)
                .type(ProjectType.CODE)
                .path("/workspace/" + id)
                .status(ProjectStatus.STARTING)
                .build();
    }

    @Test
    void startingProject_withoutContainerRow_becomesStopped() {
        Project project = project("proj-1");
        when(projectRepository.findByStatus(ProjectStatus.STARTING)).thenReturn(List.of(project));
        when(containerRepository.findByProjectId("proj-1")).thenReturn(Optional.empty());

        reconciler.reconcileStartingProjects();

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.STOPPED);
        verify(projectRepository).save(project);
        verify(activityLogService).warn(eq(LogCategory.SYSTEM), anyString(), anyString());
    }

    @Test
    void startingProject_withRunningContainerRow_becomesRunning() {
        Project project = project("proj-2");
        when(projectRepository.findByStatus(ProjectStatus.STARTING)).thenReturn(List.of(project));
        when(containerRepository.findByProjectId("proj-2")).thenReturn(Optional.of(
                Container.builder().id("c-1").dockerContainerId("docker-1")
                        .status(ContainerStatus.RUNNING).build()));

        reconciler.reconcileStartingProjects();

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.RUNNING);
        verify(projectRepository).save(project);
    }

    @Test
    void startingProject_withStoppedContainerRow_becomesStopped() {
        Project project = project("proj-3");
        when(projectRepository.findByStatus(ProjectStatus.STARTING)).thenReturn(List.of(project));
        when(containerRepository.findByProjectId("proj-3")).thenReturn(Optional.of(
                Container.builder().id("c-2").dockerContainerId("docker-2")
                        .status(ContainerStatus.STOPPED).build()));

        reconciler.reconcileStartingProjects();

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.STOPPED);
        verify(projectRepository).save(project);
    }

    @Test
    void noStartingProjects_savesNothing() {
        when(projectRepository.findByStatus(ProjectStatus.STARTING)).thenReturn(List.of());

        reconciler.reconcileStartingProjects();

        verify(projectRepository, never()).save(any());
        verifyNoInteractions(containerRepository, activityLogService);
    }
}
