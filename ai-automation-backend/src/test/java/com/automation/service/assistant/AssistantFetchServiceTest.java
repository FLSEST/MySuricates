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
import com.automation.service.assistant.fetch.BlockConfigSummaryFormatter;
import com.automation.service.assistant.fetch.DocsFetchHandler;
import com.automation.service.assistant.fetch.InventoryFetchHandler;
import com.automation.service.assistant.fetch.PoolFetchHandler;
import com.automation.service.assistant.fetch.ProjectFetchHandler;
import com.automation.service.assistant.fetch.RunFetchHandler;
import com.automation.service.assistant.fetch.WorkflowFetchHandler;
import com.automation.service.assistant.format.AssistantFormatUtils;
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

    private AssistantFetchService service;

    @BeforeEach
    void setUp() {
        BlockConfigSummaryFormatter blockConfigSummaryFormatter =
                new BlockConfigSummaryFormatter(workflowRepository);
        RunFetchHandler runFetchHandler = new RunFetchHandler(workflowRunRepository);
        WorkflowFetchHandler workflowFetchHandler =
                new WorkflowFetchHandler(workflowRepository, blockConfigSummaryFormatter);
        DocsFetchHandler docsFetchHandler = new DocsFetchHandler();
        PoolFetchHandler poolFetchHandler =
                new PoolFetchHandler(workflowPoolService, blockConfigSummaryFormatter);
        ProjectFetchHandler projectFetchHandler =
                new ProjectFetchHandler(projectRepository, projectContextService);
        InventoryFetchHandler inventoryFetchHandler = new InventoryFetchHandler(wishRepository,
                skillRepository, integrationRegistry, integrationConnectionRepository,
                workflowChainRepository, workflowRepository);
        service = new AssistantFetchService(runFetchHandler, workflowFetchHandler, docsFetchHandler,
                poolFetchHandler, projectFetchHandler, inventoryFetchHandler);
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

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("run_detail", "r1"));

        assertThat(result).contains("r1");
        assertThat(result).contains("COMPLETED");
    }

    @Test
    void executeFetch_runDetail_notFound_returnsNotFoundMessage() {
        when(workflowRunRepository.findByIdWithSteps("missing")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("run_detail", "missing"));

        assertThat(result).contains("not found");
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

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("wish_detail", "w1"));

        assertThat(result).contains("Build me a thing");
    }

    @Test
    void executeFetch_wishDetail_notFound_returnsMessage() {
        when(wishRepository.findById("missing")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("wish_detail", "missing"));

        assertThat(result).contains("not found");
    }

    @Test
    void executeFetch_runDetail_withError_includesError() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.FAILED)
                .error(java.util.Map.of("message", "Something broke"))
                .durationMs(3000L).steps(new ArrayList<>()).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));

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

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("run_detail", "r1"));

        assertThat(result).contains("Steps");
        assertThat(result).contains("COMPLETED");
        assertThat(result).contains("FAILED");
        assertThat(result).contains("error output");
    }

    @Test
    void executeFetch_stepOutput_invalidFormat_returnsError() {
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "no-slash"));

        assertThat(result).contains("Invalid format");
    }

    @Test
    void executeFetch_stepOutput_invalidStepNumber_returnsError() {
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "r1/abc"));

        assertThat(result).contains("Invalid step number");
    }

    @Test
    void executeFetch_stepOutput_runNotFound_returnsMessage() {
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "r1/1"));

        assertThat(result).contains("not found");
    }

    @Test
    void executeFetch_stepOutput_stepNotFound_returnsMessage() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .steps(new ArrayList<>()).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "r1/5"));

        assertThat(result).contains("Step 5 not found");
    }

    @Test
    void executeFetch_stepOutput_noOutput_returnsKeinOutput() {
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                .output(null).build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(step))).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "r1/1"));

        assertThat(result).contains("No output");
    }

    @Test
    void executeFetch_workflowDetail_notFound_returnsMessage() {
        when(workflowRepository.findById("wf-missing")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow_detail", "wf-missing"));

        assertThat(result).contains("not found");
    }

    @Test
    void executeFetch_workflowRuns_noRuns_returnsMessage() {
        when(workflowRunRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow_runs", "wf-1"));

        assertThat(result).contains("No runs");
    }

    @Test
    void executeFetch_projectContext_notFound_returnsMessage() {
        when(projectRepository.findById("p-missing")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("project_context", "p-missing"));

        assertThat(result).contains("not found");
    }

    @Test
    void executeFetch_docs_unknownDoc_returnsError() {
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("docs", "nonexistent-doc"));

        assertThat(result).contains("Unknown documentation");
    }

    @Test
    void executeFetch_poolWorkflows_empty_returnsMessage() {
        when(workflowPoolService.listAll()).thenReturn(List.of());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("pool_workflows", "all"));

        assertThat(result).contains("No pool workflows");
    }

    @Test
    void executeFetch_poolWorkflowDetail_notFound_returnsMessage() {
        when(workflowPoolService.findById("missing")).thenReturn(null);

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("pool_workflow", "missing"));

        assertThat(result).contains("not found");
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

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow_detail", "wf-1"));

        assertThat(result).contains("**Workflow: Release** (Alpha)");
        assertThat(result).contains("Project ID: p1");
        assertThat(result).contains("Description: Workflow fuer den Release-Build");
        assertThat(result).contains("Model: claude-sonnet");
        assertThat(result).contains("Instructions: Baue und validiere den Release.");
        assertThat(result).contains("1. [shell] Analyse");
        assertThat(result).contains("command: ls -la");
    }

    @Test
    void executeFetch_workflowRuns_withFailedRun_includesErrorAndLink() {
        WorkflowRun failedRun = WorkflowRun.builder()
                .id("run-1")
                .status(RunStatus.FAILED)
                .startedAt(java.time.Instant.parse("2026-03-15T09:00:00Z"))
                .error(java.util.Map.of("message", "bash exited 1"))
                .build();
        when(workflowRunRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(failedRun));

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow_runs", "wf-1"));

        assertThat(result).contains("**Recent runs for workflow wf-1…:**");
        assertThat(result).contains("[" + AssistantFormatUtils.fmt(failedRun.getStartedAt())
                + "] FAILED (link:/runs/run-1) — bash exited 1");
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
        assertThat(result).contains("[... skipped 100 of 8100 characters ...]");
    }

    @Test
    void executeFetch_stepOutput_longOutput_tailSurvives() {
        String longOutput = "x".repeat(9000) + "HTTP 502 Bad Gateway";
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.FAILED)
                .output(longOutput).build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.FAILED)
                .steps(new ArrayList<>(List.of(step))).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "r1/1"));

        assertThat(result).contains("HTTP 502 Bad Gateway");
    }

    @Test
    void executeFetch_stepOutput_offsetPaging_returnsWindow() {
        String longOutput = "a".repeat(9000) + "END-MARKER";
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                .output(longOutput).build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(step))).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "r1/1?offset=9000"));

        assertThat(result).contains("[showing characters 9000-9010 of 9010]");
        assertThat(result).contains("END-MARKER");
    }

    @Test
    void executeFetch_stepOutput_tailParam_returnsTail() {
        String longOutput = "x".repeat(5000) + "FINAL LINE";
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                .output(longOutput).build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(step))).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "r1/1?tail=100"));

        assertThat(result).contains("[... skipped 4910 of 5010 characters ...]");
        assertThat(result).contains("FINAL LINE");
        assertThat(result).doesNotContain("x".repeat(101));
    }

    @Test
    void executeFetch_stepOutput_invalidPagingParam_returnsError() {
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "r1/1?foo=bar"));

        assertThat(result).contains("Invalid paging parameter");
    }

    @Test
    void executeFetch_stepOutput_offsetBeyondLength_returnsExplanation() {
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                .output("short").build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(step))).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "r1/1?offset=100"));

        assertThat(result).contains("beyond output length 5");
    }

    @Test
    void executeFetch_docsWorkflowGuide_loadsFromClasspathAndReturnsToc() {
        // The maven-resources-plugin (sync-docs-from-source) bundles docs/ onto the classpath,
        // so the assistant can actually load the workflow guide at runtime. A bare doc id
        // (no section) returns the table of contents.
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("docs", "workflow-guide"));

        assertThat(result).contains("Documentation: workflow-guide");
        assertThat(result).contains("Table of contents");
        assertThat(result).doesNotContain("not available");
    }

    @Test
    void executeFetch_poolWorkflowDetail_formatsLocalizedFieldsAndBlocks() {
        PoolWorkflowEntry entry = new PoolWorkflowEntry(
                "pool-1",
                Map.of("de", "Release Vorlage"),
                Map.of("de", "Fuehrt den Release-Prozess aus."),
                "icon",
                "delivery",
                "repo",
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

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("pool_workflow", "pool-1"));

        assertThat(result).contains("**Pool workflow: Release Vorlage** (pool-1)");
        assertThat(result).contains("Description: Fuehrt den Release-Prozess aus.");
        assertThat(result).contains("Model: claude-sonnet");
        assertThat(result).contains("Instructions: Pruefe alle Artefakte.");
        assertThat(result).contains("Default variables: {branch=main}");
        assertThat(result).contains("1. [shell] Build");
    }

    @Test
    void executeFetch_projectContext_success_returnsGeneratedAnalysis() {
        var project = com.automation.entity.Project.builder().id("p1").name("Alpha").path("/srv/alpha").build();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(projectContextService.generateContext("/srv/alpha")).thenReturn("README vorhanden");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("project_context", "p1"));

        assertThat(result).contains("**Project analysis: Alpha** (path: /srv/alpha)");
        assertThat(result).contains("README vorhanden");
    }

    @Test
    void executeFetch_projectContext_blankContext_returnsHelpfulMessage() {
        var project = com.automation.entity.Project.builder().id("p1").name("Alpha").path("/srv/alpha").build();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(projectContextService.generateContext("/srv/alpha")).thenReturn(" ");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("project_context", "p1"));

        assertThat(result).contains("No project context available for 'Alpha'.");
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

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("wish_detail", "w1"));

        assertThat(result).contains("**Wish w1…** — [FAILED] Erzeuge einen Build-Workflow");
        assertThat(result).contains("Iterations: 3/5");
        assertThat(result).contains("Error: Timeout beim Deploy");
    }

    // ── Skill detail ─────────────────────────────────────────────────

    @Test
    void executeFetch_skill_notFound_returnsMessage() {
        when(skillRepository.findByIdWithCapabilities("missing")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("skill", "missing"));

        assertThat(result).contains("not found");
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

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("skill", "s1"));

        assertThat(result)
                .contains("**Skill: Web Research** (id: s1)")
                .contains("Category: RESEARCH")
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

        assertThat(result).contains("not found");
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
                .accountInfo("alex@github").build();
        when(integrationRegistry.hasProvider("github")).thenReturn(true);
        when(integrationRegistry.getProvider("github")).thenReturn(provider);
        when(integrationConnectionRepository.findAllByProviderId("github")).thenReturn(List.of(conn));

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("integration", "github"));

        assertThat(result)
                .contains("**Integration: GitHub** (id: github)")
                .contains("Status: CONNECTED (alex@github)")
                .contains("OAuth flow required: yes")
                .contains("Available actions (1)")
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
        when(integrationConnectionRepository.findAllByProviderId("slack")).thenReturn(List.of());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("integration", "slack"));

        assertThat(result).contains("Status: DISCONNECTED");
    }

    // ── Chain detail ─────────────────────────────────────────────────

    @Test
    void executeFetch_chain_notFound_returnsMessage() {
        when(workflowChainRepository.findById("missing")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("chain", "missing"));

        assertThat(result).contains("not found");
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
        List<Object[]> names = new ArrayList<>();
        names.add(new Object[]{"wf-a", "Build Workflow"});
        when(workflowRepository.findIdAndNameByIdIn(List.of("wf-a", "wf-b"))).thenReturn(names);

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("chain", "c1"));

        assertThat(result)
                .contains("**Chain: Daily Pipeline** (id: c1)")
                .contains("Stop on failure: yes")
                .contains("Workflows in Chain (2)")
                .contains("1. Build Workflow (id: wf-a) [Label: Build]")
                .contains("2. wf-b (id: wf-b)");
    }

    // ── Block-Spec ───────────────────────────────────────────────────

    @Test
    void executeFetch_blockSpec_unknownType_returnsErrorWithList() {
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("block-spec", "nonexistent"));

        assertThat(result)
                .contains("Unknown block type 'nonexistent'")
                .contains("simple")
                .contains("agentic");
    }

    @Test
    void executeFetch_blockSpec_knownType_returnsSpec() {
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("block-spec", "agentic"));

        assertThat(result)
                .contains("**Block type: agentic**")
                .contains("max_total_tokens")
                .contains("Example config");
    }

    @Test
    void executeFetch_blockSpec_allBlockTypesHaveSpec() {
        for (String type : List.of("simple", "shell", "agentic", "conditional", "foreach",
                "while", "switch", "retry", "approval", "sub-workflow",
                "skill-capability", "human-input", "integration", "document")) {
            String result = service.executeFetch(new AssistantFetchService.FetchRequest("block-spec", type));
            assertThat(result).as("Block type %s should have spec", type)
                    .contains("**Block type: " + type + "**");
        }
    }

    // ── Workflow-full ────────────────────────────────────────────────

    @Test
    void executeFetch_workflowFull_notFound_returnsMessage() {
        when(workflowRepository.findById("missing")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow-full", "missing"));

        assertThat(result).contains("not found");
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

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow-full", "wf-1"));

        assertThat(result)
                .contains("**Workflow (full): Test**")
                .contains("Model: claude-opus-4-6")
                .contains("Default inputs: {input1=default-value}")
                .contains("type: shell")
                .contains("command: ls -la");
    }

    // ── Workflow-inputs ──────────────────────────────────────────────

    @Test
    void executeFetch_workflowInputs_notFound_returnsMessage() {
        when(workflowRepository.findById("missing")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow-inputs", "missing"));

        assertThat(result).contains("not found");
    }

    @Test
    void executeFetch_workflowInputs_noContext_returnsEmpty() {
        var workflow = Workflow.builder().id("wf-1").name("NoInputs")
                .defaultContext(new java.util.HashMap<>()).build();
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(workflow));

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow-inputs", "wf-1"));

        assertThat(result)
                .contains("**Inputs for workflow: NoInputs** (id: wf-1)")
                .contains("(no default inputs defined)");
    }

    @Test
    void executeFetch_workflowInputs_withVariables_formatsMap() {
        var workflow = Workflow.builder().id("wf-1").name("WithInputs")
                .defaultContext(new java.util.HashMap<>(Map.of("path", "/tmp", "limit", "10")))
                .build();
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(workflow));

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("workflow-inputs", "wf-1"));

        assertThat(result)
                .contains("**Inputs for workflow: WithInputs**")
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
                .contains("Workflow search for 'markt'")
                .contains("1 matches")
                .contains("Markt-Radar Simap")
                .contains("wf-42")
                .contains("Proj");
    }

    @Test
    void executeFetch_findWorkflow_noMatch_returnsNotFound() {
        when(workflowRepository.findByNameContainingIgnoreCase("nope"))
                .thenReturn(List.of());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("find_workflow", "nope"));

        assertThat(result).contains("No workflow found");
    }

    @Test
    void executeFetch_findWorkflow_blankQuery_returnsHint() {
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("find_workflow", ""));

        assertThat(result).contains("Search term missing");
    }
}
