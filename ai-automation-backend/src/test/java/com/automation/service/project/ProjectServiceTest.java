package com.automation.service.project;

import com.automation.dto.request.CreateProjectRequest;
import com.automation.dto.request.UpdateProjectRequest;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ContainerService containerService;

    @Mock
    private PathValidator pathValidator;

    @InjectMocks
    private ProjectService projectService;

    private Project testProject;

    @BeforeEach
    void setUp() {
        testProject = Project.builder()
                .id("proj-1")
                .name("Test Project")
                .type(ProjectType.CODE)
                .path("/workspace/test")
                .status(ProjectStatus.STOPPED)
                .build();
    }

    @Test
    void findAll_returnsAllProjects() {
        when(projectRepository.findAll()).thenReturn(List.of(testProject));

        List<Project> result = projectService.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Test Project");
    }

    @Test
    void findById_existingProject_returnsProject() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));

        Project result = projectService.findById("proj-1");

        assertThat(result.getName()).isEqualTo("Test Project");
    }

    @Test
    void findById_nonExistent_throwsResourceNotFoundException() {
        when(projectRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.findById("unknown"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Project");
    }

    @Test
    void create_validRequest_createsProject() {
        var request = new CreateProjectRequest("New Project", null, null, ProjectType.CODE, "/workspace/new", null);
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId("proj-new");
            return p;
        });

        Project result = projectService.create(request);

        assertThat(result.getName()).isEqualTo("New Project");
        assertThat(result.getType()).isEqualTo(ProjectType.CODE);
        assertThat(result.getPath()).isEqualTo("/workspace/new");
        assertThat(result.getStatus()).isEqualTo(ProjectStatus.STOPPED);
        assertThat(result.getConfig()).isNotNull().isEmpty();
        verify(projectRepository).save(any(Project.class));
    }

    @Test
    void create_withConfig_preservesConfig() {
        Map<String, Object> config = Map.of("key", "value");
        var request = new CreateProjectRequest("Configured", null, null, ProjectType.RESEARCH, "/workspace/cfg", config);
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        Project result = projectService.create(request);

        assertThat(result.getConfig()).containsEntry("key", "value");
    }

    @Test
    void update_existingProject_updatesFields() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new UpdateProjectRequest("Updated Name", null, null, ProjectType.CONTENT, null, null);
        Project result = projectService.update("proj-1", request);

        assertThat(result.getName()).isEqualTo("Updated Name");
        assertThat(result.getType()).isEqualTo(ProjectType.CONTENT);
        assertThat(result.getPath()).isEqualTo("/workspace/test"); // unchanged
    }

    @Test
    void update_nullFields_keepsExistingValues() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new UpdateProjectRequest(null, null, null, null, null, null);
        Project result = projectService.update("proj-1", request);

        assertThat(result.getName()).isEqualTo("Test Project");
        assertThat(result.getType()).isEqualTo(ProjectType.CODE);
    }

    @Test
    void delete_runningProject_stopsContainerFirst() {
        testProject.setStatus(ProjectStatus.RUNNING);
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));

        projectService.delete("proj-1");

        verify(containerService).stopContainer(testProject);
        verify(projectRepository).delete(testProject);
    }

    @Test
    void delete_stoppedProject_deletesWithoutStopping() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));

        projectService.delete("proj-1");

        verify(containerService, never()).stopContainer(any());
        verify(projectRepository).delete(testProject);
    }

    @Test
    void startContainer_setsStatusRunningOnSuccess() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        Project result = projectService.startContainer("proj-1");

        assertThat(result.getStatus()).isEqualTo(ProjectStatus.RUNNING);
        verify(containerService).createAndStartContainer(testProject);
    }

    @Test
    void startContainer_setsStatusErrorOnFailure() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("Docker not available")).when(containerService).createAndStartContainer(any());

        Project result = projectService.startContainer("proj-1");

        assertThat(result.getStatus()).isEqualTo(ProjectStatus.ERROR);
    }

    @Test
    void stopContainer_setsStatusStopped() {
        testProject.setStatus(ProjectStatus.RUNNING);
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        Project result = projectService.stopContainer("proj-1");

        assertThat(result.getStatus()).isEqualTo(ProjectStatus.STOPPED);
        verify(containerService).stopContainer(testProject);
    }
}
