package com.automation.service.example;

import com.automation.dto.request.CreateProjectRequest;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChangeSource;
import com.automation.entity.Project;
import com.automation.entity.ProjectType;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowTemplate;
import com.automation.service.project.ProjectService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExampleProjectServiceTest {

    @TempDir
    Path base;

    private ProjectService projectService;
    private WorkflowService workflowService;
    private WorkflowTemplateService templateService;
    private ExampleProjectService service;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        workflowService = mock(WorkflowService.class);
        templateService = mock(WorkflowTemplateService.class);
        service = new ExampleProjectService(projectService, workflowService, templateService,
                mock(PlatformTransactionManager.class), base.toString(), "");
    }

    private Path target() {
        return base.resolve("example-project");
    }

    private static WorkflowTemplate template(String id, String category, boolean builtIn) {
        return WorkflowTemplate.builder()
                .id(id)
                .name(Map.of("de", "Beispiel " + id, "en", "Example " + id))
                .description(Map.of("de", "Zeigt " + id, "en", "Shows " + id))
                .category(category)
                .builtIn(builtIn)
                .blocks(List.of(BlockDefinition.builder().type("shell").name("step")
                        .config(Map.of("command", "echo ok")).build()))
                .build();
    }

    private Project project(String id, String path) {
        return Project.builder().id(id).name("p").type(ProjectType.PERSONAL).path(path).build();
    }

    @Test
    void firstCall_copiesTheBundledFiles_createsTheProject_andOneWorkflowPerExampleTemplate() throws Exception {
        when(projectService.findAll()).thenReturn(List.of());
        when(projectService.create(any())).thenAnswer(inv -> project("new", ((CreateProjectRequest) inv.getArgument(0)).path()));
        when(workflowService.findByProjectId("new")).thenReturn(List.of());
        when(templateService.findAll()).thenReturn(List.of(
                template("example-approval", "EXAMPLE", true),
                template("code-review", "CODE", true),
                template("example-own", "EXAMPLE", false)));

        ExampleProjectService.Result result = service.ensure("de");

        assertThat(result.created()).isTrue();
        assertThat(result.workflowsAdded()).isEqualTo(1);
        assertThat(target().resolve("README.md")).isRegularFile();

        ArgumentCaptor<CreateProjectRequest> projectRequest = ArgumentCaptor.forClass(CreateProjectRequest.class);
        verify(projectService).create(projectRequest.capture());
        assertThat(projectRequest.getValue().name()).isEqualTo("Beispielprojekt");
        assertThat(projectRequest.getValue().type()).isEqualTo(ProjectType.PERSONAL);
        assertThat(projectRequest.getValue().path()).isEqualTo(target().toString());

        ArgumentCaptor<CreateWorkflowRequest> workflowRequest = ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService).create(workflowRequest.capture(), eq(ChangeSource.EXAMPLE_PROJECT));
        assertThat(workflowRequest.getValue().projectId()).isEqualTo("new");
        assertThat(workflowRequest.getValue().templateId()).isEqualTo("example-approval");
        assertThat(workflowRequest.getValue().name()).isEqualTo("Beispiel example-approval");
        assertThat(workflowRequest.getValue().description()).isEqualTo("Zeigt example-approval");
        assertThat(workflowRequest.getValue().blocks()).hasSize(1);
    }

    @Test
    void secondCall_returnsTheExistingProject_andAddsOnlyMissingExamples() throws Exception {
        Files.createDirectories(target());
        Project existing = project("ex", target().toString());
        when(projectService.findAll()).thenReturn(List.of(project("other", "/somewhere/else"), existing));
        Workflow present = Workflow.builder().id("wf").templateId("example-approval").build();
        when(workflowService.findByProjectId("ex")).thenReturn(List.of(present));
        when(templateService.findAll()).thenReturn(List.of(
                template("example-approval", "EXAMPLE", true),
                template("example-switch", "EXAMPLE", true)));

        ExampleProjectService.Result result = service.ensure("en");

        assertThat(result.created()).isFalse();
        assertThat(result.project()).isSameAs(existing);
        assertThat(result.workflowsAdded()).isEqualTo(1);
        verify(projectService, never()).create(any());
        ArgumentCaptor<CreateWorkflowRequest> workflowRequest = ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService).create(workflowRequest.capture(), eq(ChangeSource.EXAMPLE_PROJECT));
        assertThat(workflowRequest.getValue().templateId()).isEqualTo("example-switch");
        assertThat(workflowRequest.getValue().name()).isEqualTo("Example example-switch");
    }

    @Test
    void anExistingDirectoryIsNeverOverwritten() throws Exception {
        Files.createDirectories(target());
        Files.writeString(target().resolve("README.md"), "mine");
        when(projectService.findAll()).thenReturn(List.of());
        when(projectService.create(any())).thenReturn(project("new", target().toString()));
        when(templateService.findAll()).thenReturn(List.of());

        service.ensure("de");

        assertThat(Files.readString(target().resolve("README.md"))).isEqualTo("mine");
    }

    @Test
    void noStagingDirectoryIsLeftBehind() throws Exception {
        when(projectService.findAll()).thenReturn(List.of());
        when(projectService.create(any())).thenReturn(project("new", target().toString()));
        when(templateService.findAll()).thenReturn(List.of());

        service.ensure("de");

        try (var entries = Files.list(base)) {
            assertThat(entries.map(p -> p.getFileName().toString())).containsExactly("example-project");
        }
    }

    @Test
    void baseDirectory_explicitSettingFirst_thenBelowTheWorkspaceRoot_elseNextToTheDatabase() {
        assertThat(ExampleProjectService.resolveBaseDirectory("/srv/examples", "/work"))
                .isEqualTo(Path.of("/srv/examples"));
        assertThat(ExampleProjectService.resolveBaseDirectory("", "/work"))
                .isEqualTo(Path.of("/work/mysuricates-examples"));
        assertThat(ExampleProjectService.resolveBaseDirectory(null, null))
                .isEqualTo(Path.of("data", "examples").toAbsolutePath().normalize());
    }
}
