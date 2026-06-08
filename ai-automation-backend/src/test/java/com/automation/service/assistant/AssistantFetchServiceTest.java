package com.automation.service.assistant;

import com.automation.entity.Capability;
import com.automation.entity.ChainEntry;
import com.automation.entity.IntegrationConnection;
import com.automation.entity.RunStatus;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.entity.WorkflowRun;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.repository.IntegrationConnectionRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.IntegrationRegistry;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantFetchServiceTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private WishRepository wishRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private IntegrationRegistry integrationRegistry;
    @Mock private IntegrationConnectionRepository integrationConnectionRepository;
    @Mock private WorkflowChainRepository workflowChainRepository;
    @Mock private WorkflowPoolService workflowPoolService;
    @Mock private ProjectContextService projectContextService;
    @Mock private AssistantContextBuilder contextBuilder;

    private AssistantFetchService service;

    @BeforeEach
    void setUp() {
        service = new AssistantFetchService(workflowRunRepository, workflowRepository,
                projectRepository, wishRepository, skillRepository,
                integrationRegistry, integrationConnectionRepository, workflowChainRepository,
                workflowPoolService, projectContextService, contextBuilder);
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
    void executeFetch_docsWorkflowGuide_loadsFromClasspathAndReturnsToc() {
        // The maven-resources-plugin (sync-docs-from-source) bundles docs/ onto the classpath,
        // so the assistant can actually load the workflow guide at runtime. A bare doc id
        // (no section) returns the table of contents.
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("docs", "workflow-guide"));

        assertThat(result).contains("Dokumentation: workflow-guide");
        assertThat(result).contains("Inhaltsübersicht");
        assertThat(result).doesNotContain("nicht verfügbar");
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

    // ── Skill detail ─────────────────────────────────────────────────

    @Test
    void executeFetch_skill_notFound_returnsMessage() {
        when(skillRepository.findByIdWithCapabilities("missing")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("skill", "missing"));

        assertThat(result).contains("nicht gefunden");
    }

    @Test
    void executeFetch_skill_formatsCapabilities() {
        Capability cap1 = Capability.builder().name("read").description("Read URL")
                .inputSchema(Map.of("url", "string")).build();
        Capability cap2 = Capability.builder().name("summarize").description("Summarize text").build();
        Skill skill = Skill.builder()
                .id("s1").name("Web Research")
                .category(SkillCategory.RESEARCH)
                .description("Recherchiert URLs").aiContext("Use for research tasks")
                .capabilities(new ArrayList<>(List.of(cap1, cap2)))
                .build();
        when(skillRepository.findByIdWithCapabilities("s1")).thenReturn(Optional.of(skill));
        when(contextBuilder.truncate("Recherchiert URLs", 200)).thenReturn("Recherchiert URLs");
        when(contextBuilder.truncate("Use for research tasks", 400)).thenReturn("Use for research tasks");
        when(contextBuilder.truncate("Read URL", 100)).thenReturn("Read URL");
        when(contextBuilder.truncate("Summarize text", 100)).thenReturn("Summarize text");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("skill", "s1"));

        assertThat(result)
                .contains("**Skill: Web Research** (id: s1)")
                .contains("Kategorie: RESEARCH")
                .contains("Capabilities (2)")
                .contains("- read — Read URL")
                .contains("inputSchema keys: [url]")
                .contains("- summarize — Summarize text");
    }

    // ── Integration detail ───────────────────────────────────────────

    @Test
    void executeFetch_integration_notFound_returnsMessage() {
        when(integrationRegistry.hasProvider("missing")).thenReturn(false);

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("integration", "missing"));

        assertThat(result).contains("nicht gefunden");
    }

    @Test
    void executeFetch_integration_connected_formatsActions() {
        IntegrationProvider provider = mock(IntegrationProvider.class);
        lenient().when(provider.getProviderId()).thenReturn("github");
        lenient().when(provider.getDisplayName()).thenReturn("GitHub");
        lenient().when(provider.getDescription()).thenReturn("Issues, PRs, Releases");
        lenient().when(provider.requiresOAuthFlow()).thenReturn(true);
        lenient().when(provider.getAvailableActions()).thenReturn(List.of(
                new IntegrationAction("create_issue", "Erzeugt ein Issue",
                        Map.of("title", "string", "body", "string"), List.of("repo"))
        ));
        IntegrationConnection conn = IntegrationConnection.builder()
                .providerId("github").status(IntegrationConnection.ConnectionStatus.CONNECTED)
                .accountInfo("flo@github").build();
        when(integrationRegistry.hasProvider("github")).thenReturn(true);
        when(integrationRegistry.getProvider("github")).thenReturn(provider);
        when(integrationConnectionRepository.findByProviderId("github")).thenReturn(Optional.of(conn));
        when(contextBuilder.truncate("Issues, PRs, Releases", 200)).thenReturn("Issues, PRs, Releases");
        when(contextBuilder.truncate("flo@github", 80)).thenReturn("flo@github");
        when(contextBuilder.truncate("Erzeugt ein Issue", 100)).thenReturn("Erzeugt ein Issue");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("integration", "github"));

        assertThat(result)
                .contains("**Integration: GitHub** (id: github)")
                .contains("Status: CONNECTED (flo@github)")
                .contains("OAuth-Flow erforderlich: ja")
                .contains("Verfügbare Actions (1)")
                .contains("create_issue")
                .contains("scopes: [repo]");
    }

    @Test
    void executeFetch_integration_disconnected_showsDisconnectedStatus() {
        IntegrationProvider provider = mock(IntegrationProvider.class);
        lenient().when(provider.getProviderId()).thenReturn("slack");
        lenient().when(provider.getDisplayName()).thenReturn("Slack");
        lenient().when(provider.getDescription()).thenReturn("Messages");
        lenient().when(provider.requiresOAuthFlow()).thenReturn(true);
        lenient().when(provider.getAvailableActions()).thenReturn(List.of());
        when(integrationRegistry.hasProvider("slack")).thenReturn(true);
        when(integrationRegistry.getProvider("slack")).thenReturn(provider);
        when(integrationConnectionRepository.findByProviderId("slack")).thenReturn(Optional.empty());
        when(contextBuilder.truncate("Messages", 200)).thenReturn("Messages");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("integration", "slack"));

        assertThat(result).contains("Status: DISCONNECTED");
    }

    // ── Chain detail ─────────────────────────────────────────────────

    @Test
    void executeFetch_chain_notFound_returnsMessage() {
        when(workflowChainRepository.findById("missing")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("chain", "missing"));

        assertThat(result).contains("nicht gefunden");
    }

    @Test
    void executeFetch_chain_formatsEntriesWithWorkflowNames() {
        ChainEntry e1 = ChainEntry.builder().workflowId("wf-a").label("Build").sortOrder(0).build();
        ChainEntry e2 = ChainEntry.builder().workflowId("wf-b").sortOrder(1).build();
        WorkflowChain chain = WorkflowChain.builder()
                .id("c1").name("Daily Pipeline").description("Tagespipeline")
                .stopOnFailure(true)
                .entries(new ArrayList<>(List.of(e1, e2)))
                .build();
        when(workflowChainRepository.findById("c1")).thenReturn(Optional.of(chain));
        when(workflowRepository.findById("wf-a")).thenReturn(Optional.of(
                Workflow.builder().id("wf-a").name("Build Workflow").build()));
        when(workflowRepository.findById("wf-b")).thenReturn(Optional.empty());
        when(contextBuilder.truncate("Tagespipeline", 200)).thenReturn("Tagespipeline");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("chain", "c1"));

        assertThat(result)
                .contains("**Chain: Daily Pipeline** (id: c1)")
                .contains("Stop on failure: ja")
                .contains("Workflows in Chain (2)")
                .contains("1. Build Workflow (id: wf-a) [Label: Build]")
                .contains("2. wf-b (id: wf-b)");
    }

    // ── Block-Spec ───────────────────────────────────────────────────

    @Test
    void executeFetch_blockSpec_unknownType_returnsErrorWithList() {
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("block-spec", "nonexistent"));

        assertThat(result)
                .contains("Unbekannter Block-Typ 'nonexistent'")
                .contains("simple")
                .contains("agentic");
    }

    @Test
    void executeFetch_blockSpec_knownType_returnsSpec() {
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("block-spec", "agentic"));

        assertThat(result)
                .contains("**Block-Typ: agentic**")
                .contains("max_total_tokens")
                .contains("Beispiel-Config");
    }

    @Test
    void executeFetch_blockSpec_allBlockTypesHaveSpec() {
        for (String type : List.of("simple", "shell", "agentic", "conditional", "foreach",
                "while", "switch", "retry", "approval", "sub-workflow",
                "skill-capability", "human-input", "integration", "document")) {
            String result = service.executeFetch(new AssistantFetchService.FetchRequest("block-spec", type));
            assertThat(result).as("Block type %s should have spec", type)
                    .contains("**Block-Typ: " + type + "**");
        }
    }

    // ── Workflow-full ────────────────────────────────────────────────

    @Test
    void executeFetch_workflowFull_notFound_returnsMessage() {
        when(workflowRepository.findById("missing")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow-full", "missing"));

        assertThat(result).contains("nicht gefunden");
    }

    @Test
    void executeFetch_workflowFull_showsFullBlockConfig() {
        var block = com.automation.entity.BlockDefinition.builder()
                .type("shell")
                .config(new java.util.HashMap<>(Map.of("command", "ls -la", "label", "List")))
                .build();
        var workflow = Workflow.builder()
                .id("wf-1").name("Test")
                .model("claude-opus-4-6")
                .defaultContext(new java.util.HashMap<>(Map.of("input1", "default-value")))
                .blocks(new ArrayList<>(List.of(block)))
                .build();
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(contextBuilder.truncate("ls -la", 300)).thenReturn("ls -la");
        when(contextBuilder.truncate("List", 300)).thenReturn("List");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow-full", "wf-1"));

        assertThat(result)
                .contains("**Workflow (Vollständig): Test**")
                .contains("Modell: claude-opus-4-6")
                .contains("Default-Inputs: {input1=default-value}")
                .contains("type: shell")
                .contains("command: ls -la");
    }

    // ── Workflow-inputs ──────────────────────────────────────────────

    @Test
    void executeFetch_workflowInputs_notFound_returnsMessage() {
        when(workflowRepository.findById("missing")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow-inputs", "missing"));

        assertThat(result).contains("nicht gefunden");
    }

    @Test
    void executeFetch_workflowInputs_noContext_returnsEmpty() {
        var workflow = Workflow.builder().id("wf-1").name("NoInputs")
                .defaultContext(new java.util.HashMap<>()).build();
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(workflow));

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow-inputs", "wf-1"));

        assertThat(result)
                .contains("**Inputs für Workflow: NoInputs** (id: wf-1)")
                .contains("(keine default Inputs definiert)");
    }

    @Test
    void executeFetch_workflowInputs_withVariables_formatsMap() {
        var workflow = Workflow.builder().id("wf-1").name("WithInputs")
                .defaultContext(new java.util.HashMap<>(Map.of("path", "/tmp", "limit", "10")))
                .build();
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(contextBuilder.truncate("/tmp", 100)).thenReturn("/tmp");
        when(contextBuilder.truncate("10", 100)).thenReturn("10");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow-inputs", "wf-1"));

        assertThat(result)
                .contains("**Inputs für Workflow: WithInputs**")
                .contains("- path = /tmp")
                .contains("- limit = 10");
    }

    @Test
    void executeFetch_findWorkflow_matches_listsNameAndId() {
        var project = com.automation.entity.Project.builder().id("p1").name("Proj").build();
        var wf = Workflow.builder().id("wf-42").name("Markt-Radar Simap").project(project).build();
        when(workflowRepository.findByNameContainingIgnoreCase("markt"))
                .thenReturn(List.of(wf));

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("find_workflow", "markt"));

        assertThat(result)
                .contains("Workflow-Suche für 'markt'")
                .contains("1 Treffer")
                .contains("Markt-Radar Simap")
                .contains("wf-42")
                .contains("Proj");
    }

    @Test
    void executeFetch_findWorkflow_noMatch_returnsNotFound() {
        when(workflowRepository.findByNameContainingIgnoreCase("nope"))
                .thenReturn(List.of());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("find_workflow", "nope"));

        assertThat(result).contains("Kein Workflow gefunden");
    }

    @Test
    void executeFetch_findWorkflow_blankQuery_returnsHint() {
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("find_workflow", ""));

        assertThat(result).contains("Suchbegriff fehlt");
    }
}
