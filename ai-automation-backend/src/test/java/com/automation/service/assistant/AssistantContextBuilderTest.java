package com.automation.service.assistant;

import com.automation.dto.AssistantChatRequest;
import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.*;
import com.automation.service.system.SystemIdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
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
}
