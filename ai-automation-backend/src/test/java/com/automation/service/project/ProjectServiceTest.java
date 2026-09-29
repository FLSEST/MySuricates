package com.automation.service.project;

import com.automation.dto.request.CreateProjectRequest;
import com.automation.dto.request.UpdateProjectRequest;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ProjectRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
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

    @Mock
    private ActivityLogService activityLogService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    @InjectMocks
    private ProjectService projectService;

    private Project testProject;

    @BeforeEach
    void setUp() {
        // TransactionTemplate.execute() asks the manager for a status, runs the callback,
        // then commits — a mock status lets the callback run and makes commit/rollback no-ops.
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        testProject = Project.builder()
                .id("proj-1")
                .name("Test Project")
                .type(ProjectType.CODE)
                .path("/workspace/test")
                .status(ProjectStatus.STOPPED)
                .build();
    }

    /**
     * Die Liste laeuft ueber {@code findActiveWithContainer()}: Nur die Join-Fetch-Variante holt
     * den Container mit, statt ihn je Projekt nachzuladen (TODO 6d0ab09f).
     */
    @Test
    void findAll_returnsAllProjects() {
        when(projectRepository.findActiveWithContainer()).thenReturn(List.of(testProject));

        List<Project> result = projectService.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Test Project");
        verify(projectRepository, never()).findByArchivedAtIsNull();
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
        var request = new CreateProjectRequest("New Project", null, null, ProjectType.CODE, "/workspace/new", null, null);
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

    /** A bundle import may not know the folder yet: blank is stored as "" and not validated. */
    @Test
    void createWithOptionalPath_blankPathSkipsValidation_otherPathsAreValidated() {
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        Project blank = projectService.createWithOptionalPath(new CreateProjectRequest(
                "Imported", null, null, ProjectType.CODE, "  ", null, null));
        assertThat(blank.getPath()).isEmpty();
        verify(pathValidator, never()).validate(any());

        doThrow(new IllegalArgumentException("Path must be an existing directory: /nope"))
                .when(pathValidator).validate("/nope");
        assertThatThrownBy(() -> projectService.createWithOptionalPath(new CreateProjectRequest(
                "Imported", null, null, ProjectType.CODE, "/nope", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_withConfig_preservesConfig() {
        Map<String, Object> config = Map.of("key", "value");
        var request = new CreateProjectRequest("Configured", null, null, ProjectType.RESEARCH, "/workspace/cfg", config, null);
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        Project result = projectService.create(request);

        assertThat(result.getConfig()).containsEntry("key", "value");
    }

    @Test
    void update_existingProject_updatesFields() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new UpdateProjectRequest("Updated Name", null, null, ProjectType.CONTENT, null, null, null);
        Project result = projectService.update("proj-1", request);

        assertThat(result.getName()).isEqualTo("Updated Name");
        assertThat(result.getType()).isEqualTo(ProjectType.CONTENT);
        assertThat(result.getPath()).isEqualTo("/workspace/test"); // unchanged
    }

    @Test
    void update_nullFields_keepsExistingValues() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new UpdateProjectRequest(null, null, null, null, null, null, null);
        Project result = projectService.update("proj-1", request);

        assertThat(result.getName()).isEqualTo("Test Project");
        assertThat(result.getType()).isEqualTo(ProjectType.CODE);
    }

    @Test
    void delete_runningProject_archivesThenStopsContainer() {
        testProject.setStatus(ProjectStatus.RUNNING);
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));

        projectService.delete("proj-1");

        verify(containerService).stopContainer(testProject);
        verify(projectRepository).save(testProject);
        assertThat(testProject.getArchivedAt()).isNotNull();
        assertThat(testProject.getStatus()).as("archived project must not stay RUNNING").isEqualTo(ProjectStatus.STOPPED);
    }

    @Test
    void delete_stoppedProject_deletesWithoutStopping() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));

        projectService.delete("proj-1");

        verify(containerService, never()).stopContainer(any());
        verify(projectRepository).save(testProject);
        assertThat(testProject.getArchivedAt()).isNotNull();
        assertThat(testProject.getStatus()).isEqualTo(ProjectStatus.STOPPED);
    }

    @Test
    void findArchived_returnsArchivedProjects() {
        Project archivedProject = Project.builder()
                .id("proj-archived")
                .name("Archived Project")
                .type(ProjectType.CODE)
                .path("/workspace/archived")
                .status(ProjectStatus.STOPPED)
                .archivedAt(Instant.now())
                .build();
        when(projectRepository.findByArchivedAtIsNotNull()).thenReturn(List.of(archivedProject));

        List<Project> result = projectService.findArchived();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getArchivedAt()).isNotNull();
    }

    @Test
    void restore_archivedProject_clearsArchivedAt() {
        testProject.setArchivedAt(Instant.now());
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        Project result = projectService.restore("proj-1");

        assertThat(result.getArchivedAt()).isNull();
        verify(projectRepository).save(testProject);
    }

    @Test
    void restore_nonExistent_throwsResourceNotFoundException() {
        when(projectRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.restore("unknown"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Project");
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
