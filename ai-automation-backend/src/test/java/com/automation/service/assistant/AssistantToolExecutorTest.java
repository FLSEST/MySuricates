package com.automation.service.assistant;

import com.automation.config.AssistantGuardrailsConfig;
import com.automation.dto.PendingTool;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ChangeSource;
import com.automation.entity.Todo;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.entity.WorkflowRun;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.chain.ChainService;
import com.automation.service.todo.TodoService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssistantToolExecutorTest {

    @Mock private TodoService todoService;
    @Mock private WorkflowService workflowService;
    @Mock private WorkflowExecutor workflowExecutor;
    @Mock private ChainService chainService;
    @Mock private ActivityLogService activityLogService;

    private AssistantToolExecutor executor;
    private PendingToolRegistry registry;
    private AssistantGuardrailsConfig guardrailsConfig;

    @BeforeEach
    void setUp() {
        registry = new PendingToolRegistry();
        guardrailsConfig = new AssistantGuardrailsConfig();
        ObjectMapper mapper = new ObjectMapper();
        executor = new AssistantToolExecutor(
                new AssistantTodoTools(todoService, mapper),
                new AssistantWorkflowTools(workflowService, workflowExecutor, mapper),
                new AssistantChainTools(chainService, mapper),
                registry,
                new AssistantToolValidator(mapper), guardrailsConfig, activityLogService, ModuleRegistry.allEnabled(),
                describer(mapper));
    }

    /** Describer without data: every id lookup finds nothing, so ids fall back to their short form. */
    private static PendingToolDescriber describer(ObjectMapper mapper) {
        return new PendingToolDescriber(mapper, mock(WorkflowRepository.class),
                mock(WorkflowRunRepository.class), mock(TodoRepository.class));
    }

    /**
     * Test helper: simulates the full extract → confirm flow. Takes the cleaned response
     * (with {@code [Aktion wartet auf Bestätigung: …]} placeholders), confirms each pending
     * tool, and substitutes the placeholder with the tool's result message — preserving the
     * surrounding prose so existing tests can still assert on "Ich erstelle: …" etc.
     */
    private String runAllTools(String input) {
        var extraction = executor.extractTools(input);
        if (extraction.pendingTools().isEmpty()) {
            return extraction.cleanedResponse();
        }
        String result = extraction.cleanedResponse();
        for (PendingTool t : extraction.pendingTools()) {
            String placeholder = "[Action awaiting confirmation: " + t.description() + "]";
            ToolExecutionResult r = executor.executeConfirmed(t);
            result = result.replace(placeholder, r.message());
        }
        return result;
    }

    @Test
    void executeTools_noToolTags_returnsUnchanged() {
        String input = "Hier ist eine normale Antwort ohne Tools.";
        assertThat(runAllTools(input)).isEqualTo(input);
    }

    @Test
    void executeTools_null_returnsNull() {
        assertThat(runAllTools(null)).isNull();
    }

    @Test
    void executeTools_createTodo_success() {
        Todo created = Todo.builder().id("12345678-abcd-1234").title("Test Todo").build();
        when(todoService.create(any())).thenReturn(created);

        String input = "<tool type=\"create_todo\">{\"title\": \"Test Todo\", \"priority\": \"HIGH\"}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("✓");
        assertThat(result).contains("Test Todo");
        verify(todoService).create(any());
    }

    @Test
    void executeTools_createTodo_missingTitle_returnsError() {
        String input = "<tool type=\"create_todo\">{\"description\": \"no title\"}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("⚠");
        assertThat(result).contains("title is missing");
        verify(todoService, never()).create(any());
    }

    @Test
    void executeTools_updateTodoStatus_success() {
        String input = "<tool type=\"update_todo_status\">{\"id\": \"12345678-abcd\", \"status\": \"DONE\"}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("✓");
        verify(todoService).updateStatus(eq("12345678-abcd"), any());
    }

    @Test
    void executeTools_updateTodoStatus_invalidStatus_returnsError() {
        String input = "<tool type=\"update_todo_status\">{\"id\": \"123\", \"status\": \"INVALID\"}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("⚠");
        assertThat(result).contains("Invalid status");
    }

    @Test
    void executeTools_createWorkflow_success() {
        Workflow created = Workflow.builder().id("wf-123").name("Test Workflow").build();
        when(workflowService.create(any(), eq(ChangeSource.ASSISTANT))).thenReturn(created);

        String input = "<tool type=\"create_workflow\">{\"projectId\": \"p1\", \"name\": \"Test Workflow\"}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("✓");
        assertThat(result).contains("Test Workflow");
    }

    @Test
    void executeTools_createWorkflow_missingName_returnsError() {
        String input = "<tool type=\"create_workflow\">{\"projectId\": \"p1\"}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("⚠");
        assertThat(result).contains("required field");
    }

    @Test
    void executeTools_createWorkflow_blankProjectId_returnsError() {
        String input = "<tool type=\"create_workflow\">{\"projectId\": \"   \", \"name\": \"x\"}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("⚠");
        assertThat(result).contains("required field");
    }

    @Test
    void executeTools_createWorkflow_blankName_returnsError() {
        String input = "<tool type=\"create_workflow\">{\"projectId\": \"p1\", \"name\": \"   \"}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("⚠");
        assertThat(result).contains("required field");
    }

    @Test
    void executeTools_startWorkflow_success() {
        Workflow wf = Workflow.builder().id("wf-1").name("My WF").build();
        when(workflowService.findById("wf-1")).thenReturn(wf);
        WorkflowRun run = WorkflowRun.builder().id("run-12345678").build();
        when(workflowExecutor.execute(eq(wf), any(), eq(TriggerSource.ASSISTANT))).thenReturn(run);

        String input = "<tool type=\"start_workflow\">{\"workflowId\": \"wf-1\"}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("✓");
        assertThat(result).contains("started");
    }

    @Test
    void executeTools_cancelRun_success() {
        String input = "<tool type=\"cancel_run\">{\"runId\": \"run-12345678\"}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("✓");
        verify(workflowExecutor).cancelExecution("run-12345678");
    }

    @Test
    void executeTools_deleteWorkflow_success() {
        Workflow wf = Workflow.builder().id("wf-1").name("Old WF").build();
        when(workflowService.findById("wf-1")).thenReturn(wf);

        String input = "<tool type=\"delete_workflow\">{\"workflowId\": \"wf-1\"}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("✓");
        assertThat(result).contains("deleted");
        verify(workflowService).delete("wf-1");
    }

    @Test
    void executeTools_createChain_success() {
        WorkflowChain chain = WorkflowChain.builder().id("ch-1").name("My Chain").build();
        when(chainService.create(any())).thenReturn(chain);

        String input = "<tool type=\"create_chain\">{\"name\": \"My Chain\", \"entries\": [{\"workflowId\": \"wf-1\"}]}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("✓");
        assertThat(result).contains("Chain created");
    }

    @Test
    void executeTools_createChain_chainsModuleDisabled_rejectedWithoutCreating() {
        ObjectMapper mapper = new ObjectMapper();
        executor = new AssistantToolExecutor(
                new AssistantTodoTools(todoService, mapper),
                new AssistantWorkflowTools(workflowService, workflowExecutor, mapper),
                new AssistantChainTools(chainService, mapper),
                registry,
                new AssistantToolValidator(mapper), guardrailsConfig, activityLogService,
                ModuleRegistry.of(java.util.Map.of("assistant", "true"), java.util.Map.of()), describer(mapper));

        String input = "<tool type=\"create_chain\">{\"name\": \"My Chain\", \"entries\": [{\"workflowId\": \"wf-1\"}]}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("module 'chains'").doesNotContain("✓");
        verifyNoInteractions(chainService);
    }

    @Test
    void executeTools_sanitizesFullwidthUnicode() {
        Todo created = Todo.builder().id("12345678-abcd-1234").title("Unicode Test").build();
        when(todoService.create(any())).thenReturn(created);

        // Use fullwidth characters: ＂title＂： ＂Unicode Test＂
        String input = "<tool type=\"create_todo\">{\u201ctitle\u201d: \u201cUnicode Test\u201d\uff0c \u201cpriority\u201d: \u201cHIGH\u201d}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("✓");
    }

    @Test
    void executeTools_unknownToolType_returnsError() {
        String input = "<tool type=\"unknown_tool\">{}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("Unknown tool");
    }

    @Test
    void executeTools_multipleTools_allProcessed() {
        Todo todo = Todo.builder().id("12345678-t").title("T1").build();
        when(todoService.create(any())).thenReturn(todo);

        String input = "Ich erstelle: <tool type=\"create_todo\">{\"title\": \"T1\"}</tool> und " +
                "<tool type=\"cancel_run\">{\"runId\": \"run-99998888\"}</tool> fertig.";
        String result = runAllTools(input);

        assertThat(result).contains("✓ Todo created");
        assertThat(result).contains("✓ Run cancelled");
        assertThat(result).contains("Ich erstelle:");
        assertThat(result).contains("fertig.");
        verify(todoService).create(any());
        verify(workflowExecutor).cancelExecution("run-99998888");
    }

    @Test
    void executeTools_updateWorkflow_success() {
        Workflow updated = Workflow.builder().id("wf-1").name("Updated WF").build();
        when(workflowService.update(eq("wf-1"), any(), eq(ChangeSource.ASSISTANT))).thenReturn(updated);

        String input = "<tool type=\"update_workflow\">{\"workflowId\": \"wf-1\", \"name\": \"Updated WF\"}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("✓");
        assertThat(result).contains("updated");
        verify(workflowService).update(eq("wf-1"), any(), eq(ChangeSource.ASSISTANT));
    }

    @Test
    void executeTools_updateWorkflow_missingWorkflowId_returnsError() {
        String input = "<tool type=\"update_workflow\">{\"name\": \"New Name\"}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("⚠");
        assertThat(result).contains("workflowId");
    }

    @Test
    void executeTools_startWorkflow_missingWorkflowId_returnsError() {
        String input = "<tool type=\"start_workflow\">{\"context\": {}}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("⚠");
        assertThat(result).contains("workflowId");
    }

    @Test
    void executeTools_deleteWorkflow_missingWorkflowId_returnsError() {
        String input = "<tool type=\"delete_workflow\">{}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("⚠");
        assertThat(result).contains("workflowId");
    }

    @Test
    void executeTools_cancelRun_missingRunId_returnsError() {
        String input = "<tool type=\"cancel_run\">{}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("⚠");
        assertThat(result).contains("runId");
    }

    @Test
    void executeTools_createChain_missingName_returnsError() {
        String input = "<tool type=\"create_chain\">{\"entries\": [{\"workflowId\": \"wf-1\"}]}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("⚠");
        assertThat(result).contains("name");
    }

    @Test
    void executeTools_createChain_emptyEntries_returnsError() {
        String input = "<tool type=\"create_chain\">{\"name\": \"Chain\", \"entries\": []}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("⚠");
        assertThat(result).contains("entries");
    }

    @Test
    void executeTools_createTodo_withTags_includesInResult() {
        Todo created = Todo.builder().id("12345678-abcd-1234").title("Tagged Todo").build();
        when(todoService.create(any())).thenReturn(created);

        String input = "<tool type=\"create_todo\">{\"title\": \"Tagged Todo\", \"tags\": [\"backend\", \"bug\"]}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("✓");
        assertThat(result).contains("Tags: backend, bug");
    }

    @Test
    void executeTools_createTodo_invalidPriority_fallsBackToMedium() {
        Todo created = Todo.builder().id("12345678-abcd-1234").title("Test").build();
        when(todoService.create(any())).thenReturn(created);

        String input = "<tool type=\"create_todo\">{\"title\": \"Test\", \"priority\": \"BOGUS\"}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("✓");
        assertThat(result).contains("MEDIUM");
    }

    @Test
    void executeTools_createTodo_serviceException_returnsError() {
        when(todoService.create(any())).thenThrow(new RuntimeException("DB error"));

        String input = "<tool type=\"create_todo\">{\"title\": \"Test\"}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("⚠");
        assertThat(result).contains("DB error");
    }

    @Test
    void executeTools_startWorkflow_withContext_passedToExecutor() {
        Workflow wf = Workflow.builder().id("wf-1").name("WF").build();
        when(workflowService.findById("wf-1")).thenReturn(wf);
        WorkflowRun run = WorkflowRun.builder().id("run-12345678").build();
        when(workflowExecutor.execute(eq(wf), any(), eq(TriggerSource.ASSISTANT))).thenReturn(run);

        String input = "<tool type=\"start_workflow\">{\"workflowId\": \"wf-1\", \"context\": {\"key\": \"val\"}}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("✓");
        verify(workflowExecutor).execute(eq(wf), argThat(ctx -> "val".equals(ctx.get("key"))), eq(TriggerSource.ASSISTANT));
    }

    @Test
    void executeTools_invalidJson_returnsError() {
        String input = "<tool type=\"create_todo\">{invalid json!!!}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("⚠");
    }

    @Test
    void executeTools_updateTodoStatus_missingFields_returnsError() {
        String input = "<tool type=\"update_todo_status\">{}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("⚠");
        assertThat(result).contains("required fields");
    }

    @Test
    void executeTools_createWorkflow_mapsBlocksMetadataAndDefaultContext() {
        Workflow created = Workflow.builder().id("wf-123").name("Deploy Flow").build();
        when(workflowService.create(any(), eq(ChangeSource.ASSISTANT))).thenReturn(created);

        String input = """
                <tool type="create_workflow">{
                  "projectId": "p1",
                  "name": "Deploy Flow",
                  "description": "Rollout",
                  "model": "claude-sonnet",
                  "blocks": [{"type": "shell", "config": {"command": "echo hi"}}],
                  "metadata": {"source": "assistant"},
                  "defaultContext": {"branch": "main"}
                }</tool>
                """;
        String result = runAllTools(input);

        assertThat(result).contains("✓ Workflow created");
        verify(workflowService).create(argThat(req ->
                "p1".equals(req.projectId())
                        && "Deploy Flow".equals(req.name())
                        && "Rollout".equals(req.description())
                        && "claude-sonnet".equals(req.model())
                        && req.blocks().size() == 1
                        && "shell".equals(req.blocks().get(0).getType())
                        && "echo hi".equals(req.blocks().get(0).getConfig().get("command"))
                        && "assistant".equals(req.metadata().get("source"))
                        && "main".equals(req.defaultContext().get("branch"))
        ), eq(ChangeSource.ASSISTANT));
    }

    @Test
    void executeTools_updateWorkflow_mapsBlocksMetadataAndDefaultContext() {
        Workflow updated = Workflow.builder().id("wf-1").name("Deploy Flow").build();
        when(workflowService.update(eq("wf-1"), any(), eq(ChangeSource.ASSISTANT))).thenReturn(updated);

        String input = """
                <tool type="update_workflow">{
                  "workflowId": "wf-1",
                  "name": "Deploy Flow",
                  "blocks": [{"type": "simple", "config": {"command": "Deploy now"}}],
                  "metadata": {"owner": "ops"},
                  "defaultContext": {"env": "prod"}
                }</tool>
                """;
        String result = runAllTools(input);

        assertThat(result).contains("✓ Workflow updated");
        verify(workflowService).update(eq("wf-1"), argThat(req ->
                "Deploy Flow".equals(req.name())
                        && req.blocks() != null
                        && req.blocks().size() == 1
                        && "simple".equals(req.blocks().get(0).getType())
                        && "Deploy now".equals(req.blocks().get(0).getConfig().get("command"))
                        && "ops".equals(req.metadata().get("owner"))
                        && "prod".equals(req.defaultContext().get("env"))
        ), eq(ChangeSource.ASSISTANT));
    }

    @Test
    void executeTools_createChain_mapsEntriesAndFlags() {
        WorkflowChain created = WorkflowChain.builder().id("ch-1").name("Release Chain").build();
        when(chainService.create(any())).thenReturn(created);

        String input = """
                <tool type="create_chain">{
                  "name": "Release Chain",
                  "description": "CI to prod",
                  "stopOnFailure": false,
                  "entries": [{
                    "workflowId": "wf-1",
                    "label": "Build",
                    "sortOrder": 3,
                    "stopOnFailure": true,
                    "inputContext": {"branch": "main"}
                  }]
                }</tool>
                """;
        String result = runAllTools(input);

        assertThat(result).contains("✓ Chain created");
        verify(chainService).create(argThat(req ->
                "Release Chain".equals(req.name())
                        && "CI to prod".equals(req.description())
                        && Boolean.FALSE.equals(req.stopOnFailure())
                        && req.entries().size() == 1
                        && "wf-1".equals(req.entries().get(0).workflowId())
                        && "Build".equals(req.entries().get(0).label())
                        && req.entries().get(0).sortOrder() == 3
                        && Boolean.TRUE.equals(req.entries().get(0).stopOnFailure())
                        && "main".equals(req.entries().get(0).inputContext().get("branch"))
        ));
    }

    @Test
    void executeTools_deleteWorkflow_serviceException_returnsError() {
        when(workflowService.findById("wf-1")).thenThrow(new RuntimeException("not allowed"));

        String input = "<tool type=\"delete_workflow\">{\"workflowId\": \"wf-1\"}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("⚠ Could not delete workflow: not allowed");
    }

    @Test
    void executeTools_startWorkflow_executorException_returnsError() {
        Workflow workflow = Workflow.builder().id("wf-1").name("Deploy").build();
        when(workflowService.findById("wf-1")).thenReturn(workflow);
        when(workflowExecutor.execute(eq(workflow), any(), eq(TriggerSource.ASSISTANT))).thenThrow(new RuntimeException("queue offline"));

        String input = "<tool type=\"start_workflow\">{\"workflowId\": \"wf-1\"}</tool>";
        String result = runAllTools(input);

        assertThat(result).contains("⚠ Could not start workflow: queue offline");
    }

    // ── Hard-gate semantics: extractTools MUST NOT execute ────────────────────

    @Test
    void extractTools_createTodo_registersPending_doesNotExecute() {
        String input = "<tool type=\"create_todo\">{\"title\": \"Gate me\", \"priority\": \"HIGH\"}</tool>";

        var extraction = executor.extractTools(input);

        // Tool is registered as pending, NOT executed
        assertThat(extraction.pendingTools()).hasSize(1);
        assertThat(extraction.pendingTools().get(0).type()).isEqualTo("create_todo");
        assertThat(extraction.pendingTools().get(0).id()).isNotBlank();
        assertThat(extraction.pendingTools().get(0).description())
                .contains("Create TODO").contains("Gate me").contains("HIGH");
        assertThat(extraction.cleanedResponse()).contains("Action awaiting confirmation");

        // Critically: no TodoService call happened
        verify(todoService, never()).create(any());
        // Registry holds the entry until take()
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void extractTools_pendingToolCarriesSubjectAndParams_forTheUiLanguageText() {
        String input = "<tool type=\"create_todo\">{\"title\": \"Call Anna\", \"priority\": \"low\"}</tool> "
                + "<tool type=\"start_workflow\">{\"workflowId\": \"76662293-aaaa-bbbb\"}</tool>";

        var extraction = executor.extractTools(input);

        PendingTool todo = extraction.pendingTools().get(0);
        assertThat(todo.subject()).isEqualTo("Call Anna");
        assertThat(todo.params()).containsEntry("priority", "LOW");
        PendingTool start = extraction.pendingTools().get(1);
        assertThat(start.subject()).isEqualTo("76662293…");
        assertThat(start.params()).containsEntry("ref", "id");
        assertThat(extraction.cleanedResponse())
                .contains("[Action awaiting confirmation: " + start.description() + "]")
                .contains("Start workflow (ID: 76662293…)");
    }

    @Test
    void extractTools_multipleTools_allRegistered_noneExecuted() {
        String input = "<tool type=\"create_todo\">{\"title\": \"A\"}</tool> "
                + "<tool type=\"delete_workflow\">{\"workflowId\": \"wf-9\"}</tool>";

        var extraction = executor.extractTools(input);

        assertThat(extraction.pendingTools()).hasSize(2);
        assertThat(extraction.pendingTools()).extracting(PendingTool::type)
                .containsExactly("create_todo", "delete_workflow");
        verifyNoInteractions(todoService, workflowService);
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void extractTools_disallowedMutatingTool_isStrippedAndNotRegistered() {
        String input = "Ich schlage ein TODO vor. <tool type=\"create_todo\">{\"title\":\"A\"}</tool>";

        var extraction = executor.extractTools(input, type -> false);

        assertThat(extraction.pendingTools()).isEmpty();
        assertThat(extraction.cleanedResponse()).contains("Ich schlage ein TODO vor.");
        assertThat(extraction.cleanedResponse()).doesNotContain("awaiting confirmation");
        assertThat(registry.size()).isZero();
        verifyNoInteractions(todoService, workflowService, workflowExecutor, chainService);
    }

    @Test
    void executeConfirmed_runsTheTool_andReturnsSuccessResult() {
        Todo created = Todo.builder().id("abcdef12-xxx").title("Gate me").build();
        when(todoService.create(any())).thenReturn(created);

        PendingTool tool = registry.register("create_todo",
                "TODO anlegen: \"Gate me\"",
                "{\"title\":\"Gate me\",\"priority\":\"HIGH\"}");

        ToolExecutionResult result = executor.executeConfirmed(tool);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("✓").contains("Gate me");
        assertThat(result.type()).isEqualTo("create_todo");
        assertThat(result.toolId()).isEqualTo(tool.id());
        verify(todoService).create(any());
    }

    @Test
    void executeConfirmed_toolFailure_returnsFailureResult() {
        when(todoService.create(any())).thenThrow(new RuntimeException("db full"));
        PendingTool tool = registry.register("create_todo", "TODO anlegen: \"X\"",
                "{\"title\":\"X\"}");

        ToolExecutionResult result = executor.executeConfirmed(tool);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("⚠").contains("db full");
    }

    // --- Guardrails: Validierung, Rate-Limit, Audit-Trail (TODO 9f022277) ---

    @Test
    void executeConfirmed_invalidWorkflowBlocks_rejectedBeforeDbWrite() {
        PendingTool tool = registry.register("create_workflow", "Create workflow: \"Broken\"",
                "{\"projectId\":\"p1\",\"name\":\"Broken\",\"blocks\":[{\"type\":\"quantum-magic\"}]}");

        ToolExecutionResult result = executor.executeConfirmed(tool);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("⚠").contains("quantum-magic");
        verify(workflowService, never()).create(any(), eq(ChangeSource.ASSISTANT));
    }

    @Test
    void executeConfirmed_mutatingAction_isRateLimited() {
        guardrailsConfig.setMaxActionsPerHour(2);
        Todo created = Todo.builder().id("abcdef12-xxx").title("T").build();
        when(todoService.create(any())).thenReturn(created);

        for (int i = 0; i < 2; i++) {
            PendingTool ok = registry.register("create_todo", "t", "{\"title\":\"T\"}");
            assertThat(executor.executeConfirmed(ok).success()).isTrue();
        }

        PendingTool third = registry.register("create_todo", "t", "{\"title\":\"T\"}");
        ToolExecutionResult result = executor.executeConfirmed(third);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Rate-Limit");
        verify(todoService, times(2)).create(any());
    }

    @Test
    void executeConfirmed_successfulAction_writesAuditTrail() {
        Todo created = Todo.builder().id("abcdef12-xxx").title("Audit me").build();
        when(todoService.create(any())).thenReturn(created);
        PendingTool tool = registry.register("create_todo", "t", "{\"title\":\"Audit me\"}");

        executor.executeConfirmed(tool);

        verify(activityLogService).info(eq(LogCategory.SYSTEM), contains("create_todo"), eq("AssistantToolExecutor"));
    }

    @Test
    void executeConfirmed_failedAction_writesAuditTrailAsWarning() {
        when(todoService.create(any())).thenThrow(new RuntimeException("db full"));
        PendingTool tool = registry.register("create_todo", "t", "{\"title\":\"X\"}");

        executor.executeConfirmed(tool);

        verify(activityLogService).warn(eq(LogCategory.SYSTEM), contains("create_todo"), eq("AssistantToolExecutor"));
    }
}
