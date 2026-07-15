package com.automation.service.assistant;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.response.BrokenWorkflowResponse;
import com.automation.entity.*;
import com.automation.repository.*;
import com.automation.service.assistant.context.BlockTypesIndexSection;
import com.automation.service.assistant.context.ChainsIndexSection;
import com.automation.service.assistant.context.FailedWorkflowsContextSection;
import com.automation.service.assistant.context.IntegrationsIndexSection;
import com.automation.service.assistant.context.ProjectsContextSection;
import com.automation.service.assistant.context.RecentSessionsContextSection;
import com.automation.service.assistant.context.RunsContextSection;
import com.automation.service.assistant.context.SchedulesContextSection;
import com.automation.service.assistant.context.SkillsIndexSection;
import com.automation.service.assistant.context.TodosContextSection;
import com.automation.service.assistant.context.WishesContextSection;
import com.automation.service.assistant.context.WorkflowMemoryContextSection;
import com.automation.service.assistant.context.WorkflowsIndexSection;
import com.automation.service.execution.WorkflowDebugService;
import com.automation.service.integration.IntegrationRegistry;
import com.automation.service.system.SystemIdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantContextBuilderTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private ScheduledWorkflowRepository scheduledWorkflowRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private WishRepository wishRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowMemoryRepository workflowMemoryRepository;
    @Mock private AssistantSessionRepository assistantSessionRepository;
    @Mock private SystemIdentityService systemIdentity;
    @Mock private SkillRepository skillRepository;
    @Mock private IntegrationRegistry integrationRegistry;
    @Mock private IntegrationConnectionRepository integrationConnectionRepository;
    @Mock private WorkflowChainRepository workflowChainRepository;
    @Mock private WorkflowDebugService workflowDebugService;

    private AssistantContextBuilder builder;

    @BeforeEach
    void setUp() {
        RunsContextSection runsSection = new RunsContextSection(workflowRunRepository, systemIdentity);
        TodosContextSection todosSection = new TodosContextSection(todoRepository);
        WishesContextSection wishesSection = new WishesContextSection(wishRepository);
        SchedulesContextSection schedulesSection = new SchedulesContextSection(scheduledWorkflowRepository);
        SkillsIndexSection skillsSection = new SkillsIndexSection(skillRepository);
        IntegrationsIndexSection integrationsSection = new IntegrationsIndexSection(integrationRegistry, integrationConnectionRepository);
        ChainsIndexSection chainsSection = new ChainsIndexSection(workflowChainRepository);
        BlockTypesIndexSection blockTypesSection = new BlockTypesIndexSection();
        FailedWorkflowsContextSection failedWorkflowsSection = new FailedWorkflowsContextSection(workflowDebugService);
        ProjectsContextSection projectsSection = new ProjectsContextSection(projectRepository);
        WorkflowsIndexSection workflowsSection = new WorkflowsIndexSection(workflowRepository);
        WorkflowMemoryContextSection workflowMemorySection = new WorkflowMemoryContextSection(workflowMemoryRepository);
        RecentSessionsContextSection recentSessionsSection =
                new RecentSessionsContextSection(assistantSessionRepository, new ObjectMapper());
        builder = new AssistantContextBuilder(workflowRunRepository, projectRepository,
                wishRepository, workflowRepository,
                runsSection, todosSection, wishesSection, schedulesSection,
                skillsSection, integrationsSection, chainsSection, blockTypesSection,
                failedWorkflowsSection, projectsSection, workflowsSection,
                workflowMemorySection, recentSessionsSection);
    }

    @Test
    void buildSystemPrompt_englishLanguage_instructsEnglishAnswer() {
        String result = builder.buildSystemPrompt(null, "base", "en");
        assertThat(result).contains("Always answer in English.");
        assertThat(result).doesNotContain("Always answer in German.");
    }

    @Test
    void buildSystemPrompt_germanLanguage_instructsGermanAnswer() {
        String result = builder.buildSystemPrompt(null, "base", "de");
        assertThat(result).contains("Always answer in German.");
    }

    @Test
    void buildSystemPrompt_nullLanguage_defaultsToGerman() {
        // Backward-compatible 2-arg overload and null language both fall back to German.
        assertThat(builder.buildSystemPrompt(null, "base")).contains("Always answer in German.");
        assertThat(builder.buildSystemPrompt(null, "base", null)).contains("Always answer in German.");
    }

    @Test
    void classifyPage_runs_returnsRuns() {
        assertThat(builder.classifyPage("runs")).isEqualTo(AssistantContextBuilder.PageCategory.RUNS);
        assertThat(builder.classifyPage("run")).isEqualTo(AssistantContextBuilder.PageCategory.RUNS);
    }

    @Test
    void classifyPage_workflows_returnsWorkflows() {
        assertThat(builder.classifyPage("workflows")).isEqualTo(AssistantContextBuilder.PageCategory.WORKFLOWS);
        assertThat(builder.classifyPage("workflow")).isEqualTo(AssistantContextBuilder.PageCategory.WORKFLOWS);
        assertThat(builder.classifyPage("chains")).isEqualTo(AssistantContextBuilder.PageCategory.WORKFLOWS);
    }

    @Test
    void classifyPage_todos_returnsTasks() {
        assertThat(builder.classifyPage("todos")).isEqualTo(AssistantContextBuilder.PageCategory.TASKS);
        assertThat(builder.classifyPage("projects")).isEqualTo(AssistantContextBuilder.PageCategory.TASKS);
    }

    @Test
    void classifyPage_wishes_returnsWishes() {
        assertThat(builder.classifyPage("wishes")).isEqualTo(AssistantContextBuilder.PageCategory.WISHES);
        assertThat(builder.classifyPage("factory")).isEqualTo(AssistantContextBuilder.PageCategory.WISHES);
    }

    @Test
    void classifyPage_schedules_returnsSchedules() {
        assertThat(builder.classifyPage("schedules")).isEqualTo(AssistantContextBuilder.PageCategory.SCHEDULES);
    }

    @Test
    void classifyPage_unknownPage_returnsOverview() {
        assertThat(builder.classifyPage("settings")).isEqualTo(AssistantContextBuilder.PageCategory.OVERVIEW);
        assertThat(builder.classifyPage("dashboard")).isEqualTo(AssistantContextBuilder.PageCategory.OVERVIEW);
        assertThat(builder.classifyPage(null)).isEqualTo(AssistantContextBuilder.PageCategory.OVERVIEW);
    }

    @Test
    void classifyPage_debug_returnsDebug() {
        assertThat(builder.classifyPage("debug")).isEqualTo(AssistantContextBuilder.PageCategory.DEBUG);
    }

    @Test
    void buildPrompt_withHistory_formatsConversation() {
        List<AssistantChatRequest.MessageEntry> history = List.of(
                new AssistantChatRequest.MessageEntry("user", "Hallo"),
                new AssistantChatRequest.MessageEntry("assistant", "Hi!"));

        String result = builder.buildPrompt(history, "Was geht?");

        assertThat(result).contains("Hallo");
        assertThat(result).contains("Hi!");
        assertThat(result).contains("Was geht?");
    }

    @Test
    void buildPrompt_emptyHistory_onlyCurrentMessage() {
        String result = builder.buildPrompt(List.of(), "Frage");

        assertThat(result).contains("Frage");
    }

    @Test
    void buildPrompt_nullHistory_onlyCurrentMessage() {
        String result = builder.buildPrompt(null, "Frage");

        assertThat(result).contains("Frage");
    }

    @Test
    void truncate_shortString_unchanged() {
        assertThat(builder.truncate("short", 100)).isEqualTo("short");
    }

    @Test
    void truncate_longString_truncated() {
        String long_ = "x".repeat(200);
        String result = builder.truncate(long_, 50);
        assertThat(result.length()).isLessThan(200);
        assertThat(result).contains("Output truncated");
    }

    @Test
    void truncate_null_returnsEmptyString() {
        assertThat(builder.truncate(null, 50)).isEmpty();
    }

    @Test
    void extractError_withErrorMap_returnsMessage() {
        WorkflowRun run = WorkflowRun.builder().id("r1")
                .error(Map.of("message", "Something went wrong")).build();
        String result = builder.extractError(run);
        assertThat(result).isEqualTo("Something went wrong");
    }

    @Test
    void extractError_emptyError_returnsNull() {
        WorkflowRun run = WorkflowRun.builder().id("r1").build();
        String result = builder.extractError(run);
        assertThat(result).isNull();
    }

    @Test
    void fmt_validInstant_formatsCorrectly() {
        Instant instant = Instant.parse("2026-03-15T14:30:00Z");
        String result = builder.fmt(instant);
        assertThat(result).isNotNull();
        assertThat(result).matches("\\d{2}\\.\\d{2}\\. \\d{2}:\\d{2}");
    }

    @Test
    void runLabel_withWorkflow_formatsLabel() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED).build();
        // runLabel accesses lazy-loaded workflow — in test env returns null-safe
        String result = builder.runLabel(run);
        assertThat(result).isNotNull();
    }

    @Test
    void fmt_nullInstant_returnsDash() {
        assertThat(builder.fmt(null)).isEqualTo("—");
    }

    @Test
    void runLink_noWorkflow_returnsRunPath() {
        WorkflowRun run = WorkflowRun.builder().id("run-123").build();
        String result = builder.runLink(run);
        assertThat(result).isEqualTo("/runs/run-123");
    }

    @Test
    void runLabel_noWorkflow_returnsUnbekannt() {
        WorkflowRun run = WorkflowRun.builder().id("r1").build();
        String result = builder.runLabel(run);
        assertThat(result).isEqualTo("Unknown");
    }

    @Test
    void extractError_nullErrorMap_returnsNull() {
        WorkflowRun run = WorkflowRun.builder().id("r1").error(null).build();
        assertThat(builder.extractError(run)).isNull();
    }

    @Test
    void extractError_noMessageKey_returnsNull() {
        WorkflowRun run = WorkflowRun.builder().id("r1")
                .error(Map.of("other", "data")).build();
        assertThat(builder.extractError(run)).isNull();
    }

    @Test
    void truncate_exactMaxLength_unchanged() {
        String exact = "x".repeat(50);
        assertThat(builder.truncate(exact, 50)).isEqualTo(exact);
    }

    @Nested
    class ResolveWorkingDir {

        @Test
        void withProject_returnsPath() {
            Project project = new Project();
            project.setId("p1");
            project.setPath("/home/test/project");
            when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

            var ctx = new AssistantChatRequest.PageContext("workflows", null, "p1");
            String result = builder.resolveWorkingDir(ctx);

            assertThat(result).isEqualTo("/home/test/project");
        }

        @Test
        void withProjectNoPath_returnsHome() {
            Project project = new Project();
            project.setId("p1");
            project.setPath(null);
            when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

            var ctx = new AssistantChatRequest.PageContext("workflows", null, "p1");
            String result = builder.resolveWorkingDir(ctx);

            assertThat(result).isEqualTo(System.getProperty("user.home"));
        }

        @Test
        void nullContext_returnsHome() {
            String result = builder.resolveWorkingDir(null);
            assertThat(result).isEqualTo(System.getProperty("user.home"));
        }

        @Test
        void noProjectId_returnsHome() {
            var ctx = new AssistantChatRequest.PageContext("workflows", null, null);
            String result = builder.resolveWorkingDir(ctx);
            assertThat(result).isEqualTo(System.getProperty("user.home"));
        }

        @Test
        void withWorkflowId_resolvesProjectPath() {
            Project project = new Project();
            project.setId("p1");
            project.setPath("/home/test/from-workflow");
            Workflow workflow = Workflow.builder().id("wf-1").project(project).build();
            when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(workflow));

            var ctx = new AssistantChatRequest.PageContext("workflow", "wf-1", null, "wf-1", null, "/workflows/wf-1");
            String result = builder.resolveWorkingDir(ctx);

            assertThat(result).isEqualTo("/home/test/from-workflow");
        }

        @Test
        void repositoryError_returnsHome() {
            when(projectRepository.findById("p1")).thenThrow(new RuntimeException("boom"));

            var ctx = new AssistantChatRequest.PageContext("workflows", null, "p1");
            String result = builder.resolveWorkingDir(ctx);

            assertThat(result).isEqualTo(System.getProperty("user.home"));
        }
    }

    @Nested
    class BuildSystemPrompt {

        @Test
        void includesBaseInstructions() {
            when(systemIdentity.getVersion()).thenReturn("1.0.0");
            when(workflowRepository.findAllWithActiveProject()).thenReturn(List.of());
            when(workflowRunRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
            when(workflowRunRepository.findTop10ByOrderByStartedAtDesc()).thenReturn(List.of());
            when(todoRepository.findAll()).thenReturn(List.of());
            when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
            when(scheduledWorkflowRepository.findSchedulesInRange(any(), any())).thenReturn(List.of());
            when(projectRepository.findByArchivedAtIsNull()).thenReturn(List.of());
            when(workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc()).thenReturn(List.of());
            when(assistantSessionRepository.findTop5ByOrderByStartedAtDesc()).thenReturn(List.of());

            String result = builder.buildSystemPrompt(null, "Du bist MySuricates.");

            assertThat(result).startsWith("Du bist MySuricates.");
            assertThat(result).contains("My Current State");
            assertThat(result).contains("1.0.0");
        }

        @Test
        void nullBaseInstructions_noError() {
            when(systemIdentity.getVersion()).thenReturn("1.0.0");
            when(workflowRepository.findAllWithActiveProject()).thenReturn(List.of());
            when(workflowRunRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
            when(workflowRunRepository.findTop10ByOrderByStartedAtDesc()).thenReturn(List.of());
            when(todoRepository.findAll()).thenReturn(List.of());
            when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
            when(scheduledWorkflowRepository.findSchedulesInRange(any(), any())).thenReturn(List.of());
            when(projectRepository.findByArchivedAtIsNull()).thenReturn(List.of());
            when(workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc()).thenReturn(List.of());
            when(assistantSessionRepository.findTop5ByOrderByStartedAtDesc()).thenReturn(List.of());

            String result = builder.buildSystemPrompt(null, null);

            assertThat(result).contains("My Current State");
        }

        @Test
        void withTodos_showsInFullMode() {
            when(systemIdentity.getVersion()).thenReturn("1.0.0");
            when(workflowRepository.findAllWithActiveProject()).thenReturn(List.of());
            when(workflowRunRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
            when(workflowRunRepository.findTop10ByOrderByStartedAtDesc()).thenReturn(List.of());
            when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
            when(scheduledWorkflowRepository.findSchedulesInRange(any(), any())).thenReturn(List.of());
            when(projectRepository.findByArchivedAtIsNull()).thenReturn(List.of());
            when(workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc()).thenReturn(List.of());
            when(assistantSessionRepository.findTop5ByOrderByStartedAtDesc()).thenReturn(List.of());

            Todo todo = Todo.builder().id("t1").title("Fix Bug").status(Todo.Status.OPEN)
                    .priority(Todo.Priority.HIGH).build();
            when(todoRepository.findAll()).thenReturn(List.of(todo));

            // OVERVIEW page → todos in full mode
            String result = builder.buildSystemPrompt(null, "");

            assertThat(result).contains("Open Todos");
            assertThat(result).contains("Fix Bug");
        }

        @Test
        void runsPage_compactTodos() {
            WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.RUNNING)
                    .steps(new ArrayList<>()).build();
            when(workflowRunRepository.findByIdWithSteps("run-1")).thenReturn(Optional.of(run));

            var ctx = new AssistantChatRequest.PageContext("runs", "run-1", null);
            String result = builder.buildSystemPrompt(ctx, "");

            assertThat(result).contains("Currently displayed run");
            assertThat(result).contains("Status: RUNNING");
            assertThat(result).doesNotContain("Todos:");
            assertThat(result).doesNotContain("Fix Bug");
        }

        @Test
        void pageContext_run_showsRunDetails() {
            StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                    .output("step output").build();
            WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.COMPLETED)
                    .durationMs(5000L).steps(new ArrayList<>(List.of(step))).build();
            when(workflowRunRepository.findByIdWithSteps("run-1")).thenReturn(Optional.of(run));

            var ctx = new AssistantChatRequest.PageContext("runs", "legacy-run", null, "wf-1", "run-1", "/workflows/wf-1/run/run-1");
            String result = builder.buildSystemPrompt(ctx, "");

            assertThat(result).contains("Currently displayed run");
            assertThat(result).contains("COMPLETED");
            assertThat(result).contains("Path: /workflows/wf-1/run/run-1");
            assertThat(result).contains("Workflow ID: wf-1");
            assertThat(result).contains("Run ID: run-1");
            assertThat(result).contains("Steps: 1 (COMPLETED=1)");
            assertThat(result).doesNotContain("step output");
            verify(workflowRunRepository).findByIdWithSteps("run-1");
        }

        @Test
        void runPage_failedRun_preloadsFailedStepEvidence() {
            String output = "noise ".repeat(1000) + "curl: (22) The requested URL returned error: 502 Bad Gateway";
            StepResult failedStep = StepResult.builder().stepIndex(1).status(StepStatus.FAILED)
                    .output(output)
                    .metadata(new HashMap<>(Map.of("blockType", "shell"))).build();
            StepResult okStep = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                    .output("ok").build();
            WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.FAILED)
                    .workflowName("Deploy")
                    .error(Map.of("message", "Step 1 (shell) failed: ..."))
                    .steps(new ArrayList<>(List.of(okStep, failedStep))).build();
            when(workflowRunRepository.findByIdWithSteps("run-1")).thenReturn(Optional.of(run));

            Workflow wf = Workflow.builder().id("wf-1").name("Deploy")
                    .blocks(new ArrayList<>(List.of(
                            BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("prompt", "summarize"))).build(),
                            BlockDefinition.builder().type("shell").name("Deploy step")
                                    .config(new HashMap<>(Map.of("command", "curl -f https://example.org/deploy"))).build()
                    ))).build();
            when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));

            var ctx = new AssistantChatRequest.PageContext("run", "run-1", null, "wf-1", "run-1", "/workflows/wf-1/run/run-1");
            String result = builder.buildSystemPrompt(ctx, "");

            assertThat(result).contains("Failed step 2 (shell)");
            assertThat(result).contains("curl -f https://example.org/deploy");
            assertThat(result).contains("502 Bad Gateway");
            assertThat(result).contains("[... skipped");
            assertThat(result).contains("<fetch type=\"step_output\" id=\"run-1/2\"/>");
        }

        @Test
        void runPage_failedRun_workflowDeleted_skipsBlockConfigGracefully() {
            StepResult failedStep = StepResult.builder().stepIndex(0).status(StepStatus.FAILED)
                    .output("boom: exit code 7")
                    .metadata(new HashMap<>(Map.of("blockType", "shell"))).build();
            WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.FAILED)
                    .workflowName("Deploy")
                    .steps(new ArrayList<>(List.of(failedStep))).build();
            when(workflowRunRepository.findByIdWithSteps("run-1")).thenReturn(Optional.of(run));
            when(workflowRepository.findById("wf-1")).thenReturn(Optional.empty());

            var ctx = new AssistantChatRequest.PageContext("run", "run-1", null, "wf-1", "run-1", null);
            String result = builder.buildSystemPrompt(ctx, "");

            assertThat(result).contains("Failed step 1 (shell)");
            assertThat(result).contains("boom: exit code 7");
            assertThat(result).doesNotContain("Block command");
        }

        @Test
        void runPage_failedRun_blockIndexMismatch_showsTypeOnly() {
            StepResult failedStep = StepResult.builder().stepIndex(0).status(StepStatus.FAILED)
                    .output("fail output")
                    .metadata(new HashMap<>(Map.of("blockType", "shell"))).build();
            WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.FAILED)
                    .steps(new ArrayList<>(List.of(failedStep))).build();
            when(workflowRunRepository.findByIdWithSteps("run-1")).thenReturn(Optional.of(run));

            // Workflow edited since the run: block at index 0 is now a different type
            Workflow wf = Workflow.builder().id("wf-1").name("Deploy")
                    .blocks(new ArrayList<>(List.of(
                            BlockDefinition.builder().type("simple")
                                    .config(new HashMap<>(Map.of("prompt", "stale prompt"))).build()
                    ))).build();
            when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));

            var ctx = new AssistantChatRequest.PageContext("run", "run-1", null, "wf-1", "run-1", null);
            String result = builder.buildSystemPrompt(ctx, "");

            assertThat(result).contains("Failed step 1 (shell)");
            assertThat(result).contains("fail output");
            assertThat(result).doesNotContain("stale prompt");
        }

        @Test
        void overview_includesProjectsWorkflowMemoryAndRecentSessions() {
            when(systemIdentity.getVersion()).thenReturn("1.0.0");
            when(workflowRunRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
            when(workflowRunRepository.findTop10ByOrderByStartedAtDesc()).thenReturn(List.of());
            when(todoRepository.findAll()).thenReturn(List.of());
            when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
            when(scheduledWorkflowRepository.findSchedulesInRange(any(), any())).thenReturn(List.of());

            Project alpha = Project.builder().id("p-alpha").name("Alpha").path("/work/alpha").build();
            Project beta = Project.builder().id("p-beta").name("Beta").path("/work/beta").build();
            Workflow alphaWorkflow = Workflow.builder().id("wf-alpha").name("Build Alpha").project(alpha).build();
            Workflow betaWorkflow = Workflow.builder().id("wf-beta").name("Build Beta").project(beta).build();
            when(projectRepository.findByArchivedAtIsNull()).thenReturn(List.of(alpha, beta));
            when(workflowRepository.findAllWithActiveProject()).thenReturn(List.of(alphaWorkflow, betaWorkflow));

            WorkflowMemory memory = WorkflowMemory.builder()
                    .workflowId("wf-alpha")
                    .content("Vor Shell-Ausfuehrung immer den Eingabe-Kontext validieren.")
                    .provider("assistant")
                    .active(true)
                    .build();
            when(workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc()).thenReturn(List.of(memory));

            AssistantSession session = AssistantSession.builder()
                    .id("session-1")
                    .page("dashboard")
                    .startedAt(Instant.parse("2026-03-20T10:15:00Z"))
                    .messages("""
                            [{"role":"assistant","content":"Hallo"},{"role":"user","content":"Wie ist der Status von Alpha?"}]
                            """)
                    .build();
            when(assistantSessionRepository.findTop5ByOrderByStartedAtDesc()).thenReturn(List.of(session));

            String result = builder.buildSystemPrompt(null, "Du bist MySuricates.");

            assertThat(result).contains("**My Projects (2):**");
            assertThat(result).contains("Alpha (ID: p-alpha, 1 workflows)");
            assertThat(result).contains("Beta (ID: p-beta, 1 workflows)");
            assertThat(result).contains("**My Workflows (2):**");
            assertThat(result).contains("[Build Alpha] Alpha (project: p-alpha)");
            assertThat(result).contains("## Learnings & Pitfalls");
            assertThat(result).contains("[Build Alpha]: Vor Shell-Ausfuehrung immer den Eingabe-Kontext validieren.");
            assertThat(result).contains("## Recent Conversations (Context)");
            assertThat(result).contains("User: \"Wie ist der Status von Alpha?\"");
        }

        @Test
        void workflowsPage_prioritizesFocusedProjectAndShowsActiveProject() {
            Project alpha = Project.builder().id("p-alpha").name("Alpha").path("/work/alpha").build();
            Project beta = Project.builder().id("p-beta").name("Beta").path("/work/beta").build();
            when(projectRepository.findByArchivedAtIsNull()).thenReturn(List.of(alpha, beta));
            lenient().when(workflowRepository.findByIdWithSkills("wf-alpha")).thenReturn(Optional.of(Workflow.builder()
                    .id("wf-alpha")
                    .name("A Workflow")
                    .project(alpha)
                    .build()));

            Workflow alphaWorkflow = Workflow.builder().id("wf-alpha").name("A Workflow").project(alpha).build();
            Workflow betaWorkflow = Workflow.builder().id("wf-beta").name("B Workflow").project(beta).build();
            when(workflowRepository.findAllWithActiveProject()).thenReturn(List.of(betaWorkflow, alphaWorkflow));

            var ctx = new AssistantChatRequest.PageContext("workflow", "wf-alpha", null, "wf-alpha", null, "/workflows/wf-alpha");
            String result = builder.buildSystemPrompt(ctx, "");

            assertThat(result).contains("**My Projects (2):**");
            assertThat(result).contains("**My Workflows (2):**");
            assertThat(result).contains("[A Workflow] Alpha (project: p-alpha)");
            assertThat(result).contains("[B Workflow] Beta (project: p-beta)");
            assertThat(result.indexOf("[A Workflow]")).isLessThan(result.indexOf("[B Workflow]"));
            assertThat(result).contains("Path: /workflows/wf-alpha");
            assertThat(result).contains("Active workflow: A Workflow (ID: wf-alpha)");
            assertThat(result).contains("Active project: Alpha (ID: p-alpha)");
        }

        @Test
        void wishesPage_showsWishDetailsAndFullWishContext() {
            Wish activeWish = Wish.builder()
                    .id("wish-1")
                    .originalText("Baue einen Deploy-Workflow")
                    .status(WishStatus.EXECUTING)
                    .iterationCount(2)
                    .maxIterations(5)
                    .build();
            when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(activeWish));
            when(wishRepository.findById("wish-1")).thenReturn(Optional.of(activeWish));

            var ctx = new AssistantChatRequest.PageContext("wishes", "wish-1", null);
            String result = builder.buildSystemPrompt(ctx, "");

            assertThat(result).contains("**Wishes:** 1 active, 0 failed");
            assertThat(result).contains("Wish details: [EXECUTING] Baue einen Deploy-Workflow, iterations: 2/5");
        }

        @Test
        void debugPage_includesBrokenWorkflowsSection() {
            when(workflowDebugService.getBrokenWorkflows()).thenReturn(List.of(
                    new BrokenWorkflowResponse("wf1", "Build Alpha", "Alpha", "run1", RunStatus.FAILED,
                            "boom", 2, "shell", Instant.parse("2026-06-01T10:00:00Z"),
                            Instant.parse("2026-06-01T10:01:00Z"), 60000L, null)));

            var ctx = new AssistantChatRequest.PageContext("debug", null, null, null, null, "/debug");
            String result = builder.buildSystemPrompt(ctx, "");

            assertThat(result).contains("Broken Workflows (last run failed)");
            assertThat(result).contains("Build Alpha");
            assertThat(result).contains("step 3 (shell)");
            assertThat(result).contains("boom");
            assertThat(result).contains("run1");
            assertThat(result).contains("run_detail");
        }
    }
}
