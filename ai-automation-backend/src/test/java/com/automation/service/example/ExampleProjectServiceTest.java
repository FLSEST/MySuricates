package com.automation.service.example;

import com.automation.dto.request.CreateProjectRequest;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.UpdateProjectRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChangeSource;
import com.automation.entity.Project;
import com.automation.entity.ProjectType;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowTemplate;
import com.automation.modules.ModuleRegistry;
import com.automation.service.project.ProjectService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        service = service(ModuleRegistry.allEnabled());
    }

    private ExampleProjectService service(ModuleRegistry modules) {
        return new ExampleProjectService(projectService, workflowService, templateService, modules,
                mock(PlatformTransactionManager.class), base.toString(), "");
    }

    private Path target() {
        return base.resolve("example-project");
    }

    private static WorkflowTemplate template(String id, String category, boolean builtIn) {
        return template(id, category, builtIn, "shell");
    }

    private static WorkflowTemplate template(String id, String category, boolean builtIn, String blockType) {
        return WorkflowTemplate.builder()
                .id(id)
                .name(Map.of("de", "Beispiel " + id, "en", "Example " + id))
                .description(Map.of("de", "Zeigt " + id, "en", "Shows " + id))
                .category(category)
                .builtIn(builtIn)
                .blocks(List.of(BlockDefinition.builder().type(blockType).name("step")
                        .config(Map.of("command", "echo ok")).build()))
                .build();
    }

    private Project project(String id, String path) {
        return project(id, path, null);
    }

    private Project project(String id, String path, String description) {
        return Project.builder().id(id).name("p").type(ProjectType.PERSONAL).path(path).description(description).build();
    }

    /** The README the example project shipped with first (e70307e8), word for word. */
    private static String earlierReadme() throws Exception {
        try (InputStream in = ExampleProjectServiceTest.class.getResourceAsStream("/example-project-earlier/README-e70307e8.txt")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String bundledReadme() throws Exception {
        try (InputStream in = ExampleProjectServiceTest.class.getResourceAsStream("/examples/example-project/README.md")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void firstCall_copiesTheBundledFiles_createsTheProject_andOneWorkflowPerExampleTemplate() throws Exception {
        when(projectService.findAll()).thenReturn(List.of());
        when(projectService.create(any())).thenAnswer(inv -> project("new", ((CreateProjectRequest) inv.getArgument(0)).path()));
        when(workflowService.findByProjectId("new")).thenReturn(List.of());
        when(templateService.findAll()).thenReturn(List.of(
                template("code-review", "CODE", true),
                template("blueprint-approval", "BASICS", true),
                template("blueprint-form", "BASICS", true)));

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
        assertThat(workflowRequest.getValue().templateId()).isEqualTo("blueprint-approval");
        assertThat(workflowRequest.getValue().name()).isEqualTo("Beispiel blueprint-approval");
        assertThat(workflowRequest.getValue().description()).isEqualTo("Zeigt blueprint-approval");
        assertThat(workflowRequest.getValue().blocks()).hasSize(1);
    }

    @Test
    void secondCall_returnsTheExistingProject_andAddsAMissingExample() throws Exception {
        Files.createDirectories(target());
        Project existing = project("ex", target().toString());
        when(projectService.findAll()).thenReturn(List.of(project("other", "/somewhere/else"), existing));
        Workflow own = Workflow.builder().id("wf").templateId("code-review").build();
        when(workflowService.findByProjectId("ex")).thenReturn(List.of(own));
        when(templateService.findAll()).thenReturn(List.of(template("blueprint-approval", "BASICS", true)));

        ExampleProjectService.Result result = service.ensure("en");

        assertThat(result.created()).isFalse();
        assertThat(result.project()).isSameAs(existing);
        assertThat(result.workflowsAdded()).isEqualTo(1);
        verify(projectService, never()).create(any());
        ArgumentCaptor<CreateWorkflowRequest> workflowRequest = ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService).create(workflowRequest.capture(), eq(ChangeSource.EXAMPLE_PROJECT));
        assertThat(workflowRequest.getValue().templateId()).isEqualTo("blueprint-approval");
        assertThat(workflowRequest.getValue().name()).isEqualTo("Example blueprint-approval");
    }

    @Test
    void anExampleThatIsAlreadyThere_isNotAddedTwice() throws Exception {
        Files.createDirectories(target());
        Project existing = project("ex", target().toString());
        when(projectService.findAll()).thenReturn(List.of(existing));
        Workflow present = Workflow.builder().id("wf").templateId("blueprint-approval").build();
        when(workflowService.findByProjectId("ex")).thenReturn(List.of(present));
        when(templateService.findAll()).thenReturn(List.of(template("blueprint-approval", "BASICS", true)));

        assertThat(service.ensure("de").workflowsAdded()).isZero();
        verify(workflowService, never()).create(any(), any());
    }

    /**
     * An example project from before the list grew (TODO 2d782949): it gets the new examples in
     * list order, and its existing copy — possibly changed by now — is left alone.
     */
    @Test
    void anOlderExampleProject_getsTheNewExamplesInOrder_andKeepsItsCopy() throws Exception {
        Files.createDirectories(target());
        Project existing = project("ex", target().toString());
        when(projectService.findAll()).thenReturn(List.of(existing));
        Workflow changedCopy = Workflow.builder().id("wf").templateId("blueprint-approval").name("mine").build();
        when(workflowService.findByProjectId("ex")).thenReturn(List.of(changedCopy));
        when(templateService.findAll()).thenReturn(ExampleProjectService.EXAMPLE_TEMPLATE_IDS.stream()
                .map(id -> template(id, "BASICS", true)).toList());

        ExampleProjectService.Result result = service.ensure("de");

        assertThat(result.created()).isFalse();
        assertThat(result.workflowsAdded()).isEqualTo(ExampleProjectService.EXAMPLE_TEMPLATE_IDS.size() - 1);
        ArgumentCaptor<CreateWorkflowRequest> workflowRequest = ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService, times(result.workflowsAdded()))
                .create(workflowRequest.capture(), eq(ChangeSource.EXAMPLE_PROJECT));
        assertThat(workflowRequest.getAllValues()).extracting(CreateWorkflowRequest::templateId)
                .containsExactly("blueprint-if", "blueprint-loop", "blueprint-todo", "blueprint-report");
        verify(workflowService, never()).update(any(), any(), any());
        verify(workflowService, never()).delete(any());
    }

    @Test
    void onlyShippedTemplatesCount_andAMissingOneIsSkipped() {
        when(projectService.findAll()).thenReturn(List.of());
        when(projectService.create(any())).thenReturn(project("new", target().toString()));
        when(workflowService.findByProjectId("new")).thenReturn(List.of());
        when(templateService.findAll()).thenReturn(List.of(template("blueprint-approval", "BASICS", false)));

        ExampleProjectService.Result result = service.ensure("de");

        assertThat(result.created()).isTrue();
        assertThat(result.workflowsAdded()).isZero();
        verify(workflowService, never()).create(any(), any());
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

    /**
     * The dashboard-report example needs its module (TODO c04ffb86): with the module off it is left
     * out instead of shipping a workflow that can only fail, and the next call after switching the
     * module on adds it.
     */
    @Test
    void anExampleWhoseModuleIsOff_isLeftOut_andAddedOnceTheModuleIsOn() {
        Project existing = project("ex", target().toString());
        when(projectService.findAll()).thenReturn(List.of(existing));
        when(workflowService.findByProjectId("ex")).thenReturn(List.of());
        when(templateService.findAll()).thenReturn(List.of(
                template("blueprint-approval", "BASICS", true),
                template("blueprint-report", "BASICS", true, "dashboard-report")));
        ExampleProjectService moduleOff = service(ModuleRegistry.of(Map.of("dashboard-report", "false"), Map.of()));

        assertThat(moduleOff.ensure("de").workflowsAdded()).isEqualTo(1);
        ArgumentCaptor<CreateWorkflowRequest> workflowRequest = ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService).create(workflowRequest.capture(), eq(ChangeSource.EXAMPLE_PROJECT));
        assertThat(workflowRequest.getValue().templateId()).isEqualTo("blueprint-approval");

        assertThat(service.ensure("de").workflowsAdded()).isEqualTo(2);
    }

    /**
     * An older copy (TODO c04ffb86) still says its workflows write nothing outside their run — no
     * longer true since the TODO example. Its description gets the current text in the language it
     * was written in.
     */
    @Test
    void anOlderCopyWithTheEarlierDescription_getsTheCurrentOneInItsLanguage() {
        Project existing = project("ex", target().toString(),
                "The example workflows need no AI and write nothing outside their run. "
                        + "The directory is a copy of the bundled template, so feel free to change it.");
        Project updated = project("ex", target().toString(), "updated");
        when(projectService.findAll()).thenReturn(List.of(existing));
        when(projectService.update(eq("ex"), any())).thenReturn(updated);
        when(templateService.findAll()).thenReturn(List.of());

        ExampleProjectService.Result result = service.ensure("de");

        ArgumentCaptor<UpdateProjectRequest> request = ArgumentCaptor.forClass(UpdateProjectRequest.class);
        verify(projectService).update(eq("ex"), request.capture());
        assertThat(request.getValue().description()).startsWith("The example workflows need no AI. All they leave behind");
        assertThat(request.getValue().name()).isNull();
        assertThat(request.getValue().path()).isNull();
        assertThat(result.project()).isSameAs(updated);
        assertThat(result.created()).isFalse();
    }

    @Test
    void aDescriptionTheUserChanged_staysAsItIs() {
        when(projectService.findAll()).thenReturn(List.of(project("ex", target().toString(), "Meine Spielwiese")));
        when(templateService.findAll()).thenReturn(List.of());

        service.ensure("de");

        verify(projectService, never()).update(any(), any());
    }

    @Test
    void anOlderCopyWithTheEarlierReadme_getsTheCurrentOne() throws Exception {
        Files.createDirectories(target());
        Files.writeString(target().resolve("README.md"), earlierReadme().replace("\n", "\r\n"));
        when(projectService.findAll()).thenReturn(List.of(project("ex", target().toString())));
        when(templateService.findAll()).thenReturn(List.of());

        service.ensure("de");

        assertThat(Files.readString(target().resolve("README.md"))).isEqualTo(bundledReadme());
    }

    @Test
    void aReadmeTheUserChanged_staysAsItIs() throws Exception {
        Files.createDirectories(target());
        String changed = earlierReadme() + "\nMeine Notiz.\n";
        Files.writeString(target().resolve("README.md"), changed);
        when(projectService.findAll()).thenReturn(List.of(project("ex", target().toString())));
        when(templateService.findAll()).thenReturn(List.of());

        service.ensure("de");

        assertThat(Files.readString(target().resolve("README.md"))).isEqualTo(changed);
    }

    /** The current README is not "earlier" — else every copy would be rewritten on every call. */
    @Test
    void theCurrentReadmeIsNoEarlierVersion() throws Exception {
        assertThat(ExampleProjectFiles.EARLIER_README_SHA256).doesNotContain(ExampleProjectFiles.sha256(bundledReadme()));
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
