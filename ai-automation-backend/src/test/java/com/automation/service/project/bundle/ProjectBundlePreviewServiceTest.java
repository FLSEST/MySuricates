package com.automation.service.project.bundle;

import com.automation.dto.bundle.BundlePreview;
import com.automation.entity.Project;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.project.ProjectService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Validation and what the preview shows — without a database. */
class ProjectBundlePreviewServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final WorkflowRepository workflows = mock(WorkflowRepository.class);
    private final SkillRepository skills = mock(SkillRepository.class);
    private ProjectBundlePreviewService service;

    @BeforeEach
    void setUp() {
        service = new ProjectBundlePreviewService(new BundleRequestReader(MAPPER), projects, projectService,
                workflows, skills, ModuleRegistry.allEnabled());
        when(projects.findByArchivedAtIsNull()).thenReturn(List.of(Project.builder().name("shopapp").build()));
        when(skills.findAll()).thenReturn(List.of(Skill.builder().id("s").name("git-tools").build()));
    }

    /** A valid bundle as a mutable tree — tests break one thing each. */
    private static Map<String, Object> bundle() {
        return new java.util.HashMap<>(Map.of(
                "format", "mysuricates-project-bundle",
                "formatVersion", 1,
                "project", Map.of("name", "shopapp", "type", "CODE"),
                "workflows", new ArrayList<>(List.of(
                        Map.of("key", "tests", "definition", Map.of("name", "Tests", "provider", "CLAUDE", "model", "opus",
                                "blocks", List.of(
                                        Map.of("type", "shell", "config", Map.of("command", "x".repeat(250))),
                                        Map.of("type", "simple", "config", Map.of("command", "prompt", "model", "haiku")),
                                        Map.of("type", "foreach", "config", Map.of("execution_mode", "shell",
                                                "command", "echo @@path:1@@", "items_command", "ls",
                                                "blocks", List.of(Map.of("type", "sub-workflow",
                                                        "config", Map.of("workflow_id", "@@workflow:report@@")))))),
                                "activatedSkillNames", List.of("git-tools", "missing-skill"))),
                        Map.of("key", "report", "definition", Map.of("name", "Report", "blocks", List.of(
                                Map.of("type", "sub-workflow", "config", Map.of("workflow_id", "ext-uuid"))))))),
                "chains", List.of(Map.of("key", "all", "name", "All", "entries",
                        List.of(Map.of("workflowKey", "tests"), Map.of("workflowKey", "report")))),
                "schedules", List.of(Map.of("target", Map.of("chainKey", "all"), "cronExpression", "0 0 6 * * *")),
                "paths", List.of(Map.of("token", "@@path:1@@", "original", "/tmp/shopapp", "occurrences", 1, "hint", "h"))));
    }

    private BundlePreview preview(Map<String, Object> bundle, String target) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("bundle", bundle);
        body.put("targetProjectId", target);
        return service.preview(MAPPER.writeValueAsString(body));
    }

    @Test
    void showsBlocksProvidersShellCommandsAndConflicts() throws Exception {
        Project target = Project.builder().id("t-1").name("Target").build();
        when(projectService.findById("t-1")).thenReturn(target);
        when(workflows.findByProjectId("t-1")).thenReturn(List.of(Workflow.builder().name("Report").build()));

        BundlePreview p = preview(bundle(), "t-1");

        assertThat(p.project().name()).isEqualTo("shopapp");
        assertThat(p.project().nameTaken()).isTrue();
        BundlePreview.WorkflowInfo tests = p.workflows().get(0);
        assertThat(tests.blockCount()).isEqualTo(3);
        assertThat(tests.blockTypes()).containsExactly("shell", "simple", "foreach", "sub-workflow");
        assertThat(tests.providers()).containsExactly("CLAUDE");
        assertThat(tests.models()).containsExactly("opus", "haiku");
        assertThat(tests.shellCommands()).containsExactly("x".repeat(200), "echo @@path:1@@", "ls");
        assertThat(tests.nameTakenInTarget()).isFalse();
        assertThat(p.workflows().get(1).nameTakenInTarget()).isTrue();
        assertThat(p.chains()).singleElement().satisfies(c -> assertThat(c.entries()).isEqualTo(2));
        assertThat(p.schedules()).singleElement().satisfies(s -> {
            assertThat(s.target()).isEqualTo("chain:all");
            assertThat(s.willBeDisabled()).isTrue();
        });
        assertThat(p.paths()).singleElement().satisfies(x -> assertThat(x.original()).isEqualTo("/tmp/shopapp"));
        assertThat(p.warnings()).anySatisfy(w -> assertThat(w).contains("ext-uuid"))
                .anySatisfy(w -> assertThat(w).contains("missing-skill"))
                .noneSatisfy(w -> assertThat(w).contains("'git-tools'"));
    }

    @Test
    void withoutTargetNoWorkflowIsReportedAsTaken() throws Exception {
        assertThat(preview(bundle(), null).workflows()).noneMatch(BundlePreview.WorkflowInfo::nameTakenInTarget);
    }

    @Test
    void chainsModuleOff_isAWarning() throws Exception {
        service = new ProjectBundlePreviewService(new BundleRequestReader(MAPPER), projects, projectService,
                workflows, skills, ModuleRegistry.of(Map.of(), Map.of()));

        assertThat(preview(bundle(), null).warnings()).contains(BundleWarnings.CHAINS_OFF);
    }

    @Test
    void rejectsWrongFormatAndVersion() {
        Map<String, Object> wrongFormat = bundle();
        wrongFormat.put("format", "something-else");
        Map<String, Object> wrongVersion = bundle();
        wrongVersion.put("formatVersion", 2);

        assertThatThrownBy(() -> preview(wrongFormat, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format");
        assertThatThrownBy(() -> preview(wrongVersion, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("formatVersion 2");
    }

    @Test
    void rejectsMissingFieldsDuplicateKeysAndUnresolvedReferences() {
        Map<String, Object> b = bundle();
        b.put("project", Map.of("type", "CODE"));
        @SuppressWarnings("unchecked")
        List<Object> wf = (List<Object>) b.get("workflows");
        wf.add(Map.of("key", "tests", "definition", Map.of("name", "Dup",
                "blocks", List.of(Map.of("type", "sub-workflow", "config", Map.of("workflow_id", "@@workflow:nope@@"))),
                "instructions", "see @@path:9@@")));
        wf.add(Map.of("key", "nameless", "definition", Map.of()));
        b.put("chains", List.of(Map.of("key", "c", "name", "C", "entries", List.of(Map.of("workflowKey", "ghost")))));
        b.put("schedules", List.of(Map.of("target", Map.of("chainKey", "all"), "cronExpression", "not a cron")));

        assertThatThrownBy(() -> preview(b, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project.name is required")
                .hasMessageContaining("duplicate workflow key 'tests'")
                .hasMessageContaining("workflow 'nameless': definition.name is required")
                .hasMessageContaining("unknown workflow key 'nope'")
                .hasMessageContaining("chain 'c' references unknown workflow key 'ghost'")
                .hasMessageContaining("unknown chain key 'all'")
                .hasMessageContaining("@@path:9@@ is used but not listed");
    }

    @Test
    void rejectsGarbageAndOversizedBodies() {
        assertThatThrownBy(() -> service.preview("{not json")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> service.preview("{}")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'bundle' is missing");
        String huge = "{\"x\":\"" + "a".repeat(BundleRequestReader.MAX_CHARS) + "\"}";
        assertThatThrownBy(() -> service.preview(huge)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }
}
