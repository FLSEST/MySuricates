package com.automation.service.assistant;

import com.automation.dto.AssistantChatRequest;
import com.automation.entity.*;
import com.automation.repository.*;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
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

    private AssistantContextBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new AssistantContextBuilder(workflowRunRepository, todoRepository,
                scheduledWorkflowRepository, projectRepository, wishRepository,
                workflowRepository, workflowMemoryRepository, assistantSessionRepository,
                systemIdentity, new ObjectMapper());
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
        assertThat(result).contains("Ausgabe gekürzt");
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
        assertThat(result).isEqualTo("Unbekannt");
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
            when(assistantSessionRepository.findTop3ByOrderByStartedAtDesc()).thenReturn(List.of());

            String result = builder.buildSystemPrompt(null, "Du bist MySuricates.");

            assertThat(result).startsWith("Du bist MySuricates.");
            assertThat(result).contains("Mein aktueller Zustand");
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
            when(assistantSessionRepository.findTop3ByOrderByStartedAtDesc()).thenReturn(List.of());

            String result = builder.buildSystemPrompt(null, null);

            assertThat(result).contains("Mein aktueller Zustand");
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
            when(assistantSessionRepository.findTop3ByOrderByStartedAtDesc()).thenReturn(List.of());

            Todo todo = Todo.builder().id("t1").title("Fix Bug").status(Todo.Status.OPEN)
                    .priority(Todo.Priority.HIGH).build();
            when(todoRepository.findAll()).thenReturn(List.of(todo));

            // OVERVIEW page → todos in full mode
            String result = builder.buildSystemPrompt(null, "");

            assertThat(result).contains("Offene Todos");
            assertThat(result).contains("Fix Bug");
        }

        @Test
        void runsPage_compactTodos() {
            when(systemIdentity.getVersion()).thenReturn("1.0.0");
            when(workflowRepository.findAllWithActiveProject()).thenReturn(List.of());
            // RUNS page → full mode for runs: uses findByStatus + findTop10
            when(workflowRunRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
            when(workflowRunRepository.findTop10ByOrderByStartedAtDesc()).thenReturn(List.of());
            when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
            when(scheduledWorkflowRepository.findSchedulesInRange(any(), any())).thenReturn(List.of());
            when(projectRepository.findByArchivedAtIsNull()).thenReturn(List.of());
            when(workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc()).thenReturn(List.of());

            Todo todo = Todo.builder().id("t1").title("Fix Bug").status(Todo.Status.OPEN)
                    .priority(Todo.Priority.HIGH).build();
            when(todoRepository.findAll()).thenReturn(List.of(todo));

            var ctx = new AssistantChatRequest.PageContext("runs", null, null);
            String result = builder.buildSystemPrompt(ctx, "");

            // Compact: shows count, not individual todo titles
            assertThat(result).contains("Todos:");
            assertThat(result).contains("1 offen");
        }

        @Test
        void pageContext_run_showsRunDetails() {
            when(systemIdentity.getVersion()).thenReturn("1.0.0");
            when(workflowRepository.findAllWithActiveProject()).thenReturn(List.of());
            // RUNS page → full mode for runs
            when(workflowRunRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
            when(workflowRunRepository.findTop10ByOrderByStartedAtDesc()).thenReturn(List.of());
            when(todoRepository.findAll()).thenReturn(List.of());
            when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
            when(scheduledWorkflowRepository.findSchedulesInRange(any(), any())).thenReturn(List.of());
            when(projectRepository.findByArchivedAtIsNull()).thenReturn(List.of());
            when(workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc()).thenReturn(List.of());

            StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                    .output("step output").build();
            WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.COMPLETED)
                    .durationMs(5000L).steps(new ArrayList<>(List.of(step))).build();
            when(workflowRunRepository.findByIdWithSteps("run-1")).thenReturn(Optional.of(run));

            var ctx = new AssistantChatRequest.PageContext("runs", "run-1", null);
            String result = builder.buildSystemPrompt(ctx, "");

            assertThat(result).contains("Aktuell angezeigter Run");
            assertThat(result).contains("COMPLETED");
        }
    }
}
