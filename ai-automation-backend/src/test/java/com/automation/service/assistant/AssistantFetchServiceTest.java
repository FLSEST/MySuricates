package com.automation.service.assistant;

import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.entity.WorkflowRun;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.pool.WorkflowPoolService;
import com.automation.service.project.ProjectContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantFetchServiceTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private WishRepository wishRepository;
    @Mock private WorkflowPoolService workflowPoolService;
    @Mock private ProjectContextService projectContextService;
    @Mock private AssistantContextBuilder contextBuilder;

    private AssistantFetchService service;

    @BeforeEach
    void setUp() {
        service = new AssistantFetchService(workflowRunRepository, workflowRepository,
                projectRepository, wishRepository, workflowPoolService,
                projectContextService, contextBuilder);
    }

    @Test
    void parseFetchRequests_validTags_parsesList() {
        String response = "Text <fetch type=\"run_detail\" id=\"r1\"/> more <fetch type=\"wish_detail\" id=\"w1\"/>";
        List<AssistantFetchService.FetchRequest> result = service.parseFetchRequests(response);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).type()).isEqualTo("run_detail");
        assertThat(result.get(0).id()).isEqualTo("r1");
        assertThat(result.get(1).type()).isEqualTo("wish_detail");
    }

    @Test
    void parseFetchRequests_noTags_emptyList() {
        assertThat(service.parseFetchRequests("plain text")).isEmpty();
    }

    @Test
    void parseFetchRequests_null_emptyList() {
        assertThat(service.parseFetchRequests(null)).isEmpty();
    }

    @Test
    void stripFetchTags_removesTags() {
        String input = "Before <fetch type=\"run_detail\" id=\"r1\"/> After";
        String result = service.stripFetchTags(input);
        assertThat(result).isEqualTo("Before  After");
    }

    @Test
    void stripFetchTags_null_returnsNull() {
        assertThat(service.stripFetchTags(null)).isNull();
    }

    @Test
    void executeFetch_runDetail_formatsRun() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .durationMs(5000L).steps(new ArrayList<>()).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));
        when(contextBuilder.runLabel(run)).thenReturn("TestWorkflow (TestProject)");
        when(contextBuilder.extractError(run)).thenReturn(null);

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("run_detail", "r1"));

        assertThat(result).contains("r1");
        assertThat(result).contains("COMPLETED");
    }

    @Test
    void executeFetch_runDetail_notFound_returnsNotFoundMessage() {
        when(workflowRunRepository.findByIdWithSteps("missing")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("run_detail", "missing"));

        assertThat(result).contains("nicht gefunden");
    }

    @Test
    void executeFetch_stepOutput_validIndex_returnsOutput() {
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                .output("step output text").build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(step))).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));
        // step_output uses 1-based indexing
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "r1/1"));

        assertThat(result).contains("step output text");
    }

    @Test
    void executeFetch_unknownType_returnsEmpty() {
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("nonexistent", "id"));
        assertThat(result).isEmpty();
    }

    @Test
    void executeFetch_wishDetail_formatsWish() {
        Wish wish = Wish.builder().id("w1").originalText("Build me a thing").build();
        when(wishRepository.findById("w1")).thenReturn(Optional.of(wish));
        when(contextBuilder.truncate("Build me a thing", 200)).thenReturn("Build me a thing");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("wish_detail", "w1"));

        assertThat(result).contains("Build me a thing");
    }

    @Test
    void executeFetch_wishDetail_notFound_returnsMessage() {
        when(wishRepository.findById("missing")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("wish_detail", "missing"));

        assertThat(result).contains("nicht gefunden");
    }

    @Test
    void executeFetch_runDetail_withError_includesError() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.FAILED)
                .durationMs(3000L).steps(new ArrayList<>()).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));
        when(contextBuilder.runLabel(run)).thenReturn("TestWF");
        when(contextBuilder.extractError(run)).thenReturn("Something broke");
        when(contextBuilder.truncate("Something broke", 600)).thenReturn("Something broke");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("run_detail", "r1"));

        assertThat(result).contains("FAILED");
        assertThat(result).contains("Something broke");
    }

    @Test
    void executeFetch_runDetail_withSteps_formatsSteps() {
        StepResult step1 = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).build();
        StepResult step2 = StepResult.builder().stepIndex(1).status(StepStatus.FAILED)
                .output("error output").build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.FAILED)
                .steps(new ArrayList<>(List.of(step1, step2))).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));
        when(contextBuilder.runLabel(run)).thenReturn("TestWF");
        when(contextBuilder.extractError(run)).thenReturn(null);
        when(contextBuilder.truncate("error output", 800)).thenReturn("error output");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("run_detail", "r1"));

        assertThat(result).contains("Schritte");
        assertThat(result).contains("COMPLETED");
        assertThat(result).contains("FAILED");
        assertThat(result).contains("error output");
    }

    @Test
    void executeFetch_stepOutput_invalidFormat_returnsError() {
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "no-slash"));

        assertThat(result).contains("Ungültiges Format");
    }

    @Test
    void executeFetch_stepOutput_invalidStepNumber_returnsError() {
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "r1/abc"));

        assertThat(result).contains("Schrittnummer ungültig");
    }

    @Test
    void executeFetch_stepOutput_runNotFound_returnsMessage() {
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "r1/1"));

        assertThat(result).contains("nicht gefunden");
    }

    @Test
    void executeFetch_stepOutput_stepNotFound_returnsMessage() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .steps(new ArrayList<>()).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "r1/5"));

        assertThat(result).contains("Step 5 nicht gefunden");
    }

    @Test
    void executeFetch_stepOutput_noOutput_returnsKeinOutput() {
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                .output(null).build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(step))).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "r1/1"));

        assertThat(result).contains("Kein Output");
    }

    @Test
    void executeFetch_workflowDetail_notFound_returnsMessage() {
        when(workflowRepository.findById("wf-missing")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow_detail", "wf-missing"));

        assertThat(result).contains("nicht gefunden");
    }

    @Test
    void executeFetch_workflowRuns_noRuns_returnsMessage() {
        when(workflowRunRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow_runs", "wf-1"));

        assertThat(result).contains("Keine Runs");
    }

    @Test
    void executeFetch_projectContext_notFound_returnsMessage() {
        when(projectRepository.findById("p-missing")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("project_context", "p-missing"));

        assertThat(result).contains("nicht gefunden");
    }

    @Test
    void executeFetch_docs_unknownDoc_returnsError() {
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("docs", "nonexistent-doc"));

        assertThat(result).contains("Unbekannte Dokumentation");
    }

    @Test
    void executeFetch_poolWorkflows_empty_returnsMessage() {
        when(workflowPoolService.listAll()).thenReturn(List.of());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("pool_workflows", "all"));

        assertThat(result).contains("Keine Pool-Workflows");
    }

    @Test
    void executeFetch_poolWorkflowDetail_notFound_returnsMessage() {
        when(workflowPoolService.findById("missing")).thenReturn(null);

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("pool_workflow", "missing"));

        assertThat(result).contains("nicht gefunden");
    }

    @Test
    void parseFetchRequests_multipleTags_allParsed() {
        String response = "<fetch type=\"a\" id=\"1\"/><fetch type=\"b\" id=\"2\"/><fetch type=\"c\" id=\"3\"/>";
        List<AssistantFetchService.FetchRequest> result = service.parseFetchRequests(response);

        assertThat(result).hasSize(3);
        assertThat(result.get(2).type()).isEqualTo("c");
        assertThat(result.get(2).id()).isEqualTo("3");
    }

    @Test
    void stripFetchTags_multipleTags_allRemoved() {
        String input = "A <fetch type=\"x\" id=\"1\"/> B <fetch type=\"y\" id=\"2\"/> C";
        String result = service.stripFetchTags(input);

        assertThat(result).doesNotContain("<fetch");
        assertThat(result).contains("A");
        assertThat(result).contains("C");
    }

    @Test
    void executeFetch_workflowDetail_formatsProjectMetadataAndBlocks() {
        var project = com.automation.entity.Project.builder().id("p1").name("Alpha").build();
        var block = com.automation.entity.BlockDefinition.builder()
                .type("shell")
                .config(Map.of("label", "Analyse", "command", "ls -la"))
                .build();
        var workflow = com.automation.entity.Workflow.builder()
                .id("wf-1")
                .name("Release")
                .project(project)
                .description("Workflow fuer den Release-Build")
                .model("claude-sonnet")
                .instructions("Baue und validiere den Release.")
                .blocks(List.of(block))
                .build();
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(contextBuilder.truncate("Workflow fuer den Release-Build", 150)).thenReturn("Workflow fuer den Release-Build");
        when(contextBuilder.truncate("Baue und validiere den Release.", 400)).thenReturn("Baue und validiere den Release.");
        when(contextBuilder.truncate("ls -la", 300)).thenReturn("ls -la");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow_detail", "wf-1"));

        assertThat(result).contains("**Workflow: Release** (Alpha)");
        assertThat(result).contains("Projekt-ID: p1");
        assertThat(result).contains("Beschreibung: Workflow fuer den Release-Build");
        assertThat(result).contains("Modell: claude-sonnet");
        assertThat(result).contains("Instruktionen: Baue und validiere den Release.");
        assertThat(result).contains("1. [shell] Analyse");
        assertThat(result).contains("command: ls -la");
    }

    @Test
    void executeFetch_workflowRuns_withFailedRun_includesErrorAndLink() {
        WorkflowRun failedRun = WorkflowRun.builder()
                .id("run-1")
                .status(RunStatus.FAILED)
                .startedAt(java.time.Instant.parse("2026-03-15T09:00:00Z"))
                .build();
        when(workflowRunRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(failedRun));
        when(contextBuilder.fmt(failedRun.getStartedAt())).thenReturn("15.03. 09:00");
        when(contextBuilder.runLink(failedRun)).thenReturn("/runs/run-1");
        when(contextBuilder.extractError(failedRun)).thenReturn("bash exited 1");
        when(contextBuilder.truncate("bash exited 1", 100)).thenReturn("bash exited 1");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow_runs", "wf-1"));

        assertThat(result).contains("**Letzte Runs für Workflow wf-1…:**");
        assertThat(result).contains("[15.03. 09:00] FAILED (link:/runs/run-1) — bash exited 1");
    }

    @Test
    void executeFetch_stepOutput_longOutput_isTruncated() {
        String longOutput = "x".repeat(8100);
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                .durationMs(1200L).output(longOutput).build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(step))).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "r1/1"));

        assertThat(result).contains("**Step 1** (COMPLETED) — 1s");
        assertThat(result).contains("[Ausgabe gekürzt: 8000 von 8100 Zeichen]");
    }

    @Test
    void executeFetch_docsWorkflowGuide_whenUnavailable_returnsHelpfulMessage() {
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("docs", "workflow-guide"));

        assertThat(result).contains("Dokumentation 'workflow-guide' nicht verfügbar.");
    }

    @Test
    void executeFetch_poolWorkflowDetail_formatsLocalizedFieldsAndBlocks() {
        PoolWorkflowEntry entry = new PoolWorkflowEntry(
                "pool-1",
                Map.of("de", "Release Vorlage"),
                Map.of("de", "Fuehrt den Release-Prozess aus."),
                "icon",
                "delivery",
                List.of("release", "ci"),
                List.of(com.automation.entity.BlockDefinition.builder()
                        .type("shell")
                        .config(Map.of("label", "Build", "command", "./gradlew build"))
                        .build()),
                "Pruefe alle Artefakte.",
                "claude-sonnet",
                "1.0.0",
                "team",
                Map.of("branch", "main"),
                Map.of(),
                "release.json"
        );
        when(workflowPoolService.findById("pool-1")).thenReturn(entry);
        when(contextBuilder.truncate("Pruefe alle Artefakte.", 500)).thenReturn("Pruefe alle Artefakte.");
        when(contextBuilder.truncate("./gradlew build", 300)).thenReturn("./gradlew build");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("pool_workflow", "pool-1"));

        assertThat(result).contains("**Pool-Workflow: Release Vorlage** (pool-1)");
        assertThat(result).contains("Beschreibung: Fuehrt den Release-Prozess aus.");
        assertThat(result).contains("Modell: claude-sonnet");
        assertThat(result).contains("Instruktionen: Pruefe alle Artefakte.");
        assertThat(result).contains("Default-Variablen: {branch=main}");
        assertThat(result).contains("1. [shell] Build");
    }

    @Test
    void executeFetch_projectContext_success_returnsGeneratedAnalysis() {
        var project = com.automation.entity.Project.builder().id("p1").name("Alpha").path("/srv/alpha").build();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(projectContextService.generateContext("/srv/alpha")).thenReturn("README vorhanden");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("project_context", "p1"));

        assertThat(result).contains("**Projekt-Analyse: Alpha** (Pfad: /srv/alpha)");
        assertThat(result).contains("README vorhanden");
    }

    @Test
    void executeFetch_projectContext_blankContext_returnsHelpfulMessage() {
        var project = com.automation.entity.Project.builder().id("p1").name("Alpha").path("/srv/alpha").build();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(projectContextService.generateContext("/srv/alpha")).thenReturn(" ");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("project_context", "p1"));

        assertThat(result).contains("Kein Projekt-Kontext für 'Alpha' verfügbar.");
    }

    @Test
    void executeFetch_wishDetail_withError_includesIterationsAndError() {
        Wish wish = Wish.builder()
                .id("w1")
                .status(WishStatus.FAILED)
                .originalText("Erzeuge einen Build-Workflow")
                .iterationCount(3)
                .maxIterations(5)
                .error("Timeout beim Deploy")
                .build();
        when(wishRepository.findById("w1")).thenReturn(Optional.of(wish));
        when(contextBuilder.truncate("Erzeuge einen Build-Workflow", 200)).thenReturn("Erzeuge einen Build-Workflow");
        when(contextBuilder.truncate("Timeout beim Deploy", 300)).thenReturn("Timeout beim Deploy");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("wish_detail", "w1"));

        assertThat(result).contains("**Wish w1…** — [FAILED] Erzeuge einen Build-Workflow");
        assertThat(result).contains("Iterationen: 3/5");
        assertThat(result).contains("Fehler: Timeout beim Deploy");
    }
}
