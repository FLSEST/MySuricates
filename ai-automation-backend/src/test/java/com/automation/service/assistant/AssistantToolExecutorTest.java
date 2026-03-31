package com.automation.service.assistant;

import com.automation.entity.Todo;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.entity.WorkflowRun;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssistantToolExecutorTest {

    @Mock private TodoService todoService;
    @Mock private WorkflowService workflowService;
    @Mock private WorkflowExecutor workflowExecutor;
    @Mock private ChainService chainService;

    private AssistantToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new AssistantToolExecutor(todoService, workflowService,
                workflowExecutor, chainService, new ObjectMapper());
    }

    @Test
    void executeTools_noToolTags_returnsUnchanged() {
        String input = "Hier ist eine normale Antwort ohne Tools.";
        assertThat(executor.executeTools(input)).isEqualTo(input);
    }

    @Test
    void executeTools_null_returnsNull() {
        assertThat(executor.executeTools(null)).isNull();
    }

    @Test
    void executeTools_createTodo_success() {
        Todo created = Todo.builder().id("12345678-abcd-1234").title("Test Todo").build();
        when(todoService.create(any())).thenReturn(created);

        String input = "<tool type=\"create_todo\">{\"title\": \"Test Todo\", \"priority\": \"HIGH\"}</tool>";
        String result = executor.executeTools(input);

        assertThat(result).contains("✓");
        assertThat(result).contains("Test Todo");
        verify(todoService).create(any());
    }

    @Test
    void executeTools_createTodo_missingTitle_returnsError() {
        String input = "<tool type=\"create_todo\">{\"description\": \"no title\"}</tool>";
        String result = executor.executeTools(input);

        assertThat(result).contains("⚠");
        assertThat(result).contains("Titel fehlt");
        verify(todoService, never()).create(any());
    }

    @Test
    void executeTools_updateTodoStatus_success() {
        String input = "<tool type=\"update_todo_status\">{\"id\": \"12345678-abcd\", \"status\": \"DONE\"}</tool>";
        String result = executor.executeTools(input);

        assertThat(result).contains("✓");
        verify(todoService).updateStatus(eq("12345678-abcd"), any());
    }

    @Test
    void executeTools_updateTodoStatus_invalidStatus_returnsError() {
        String input = "<tool type=\"update_todo_status\">{\"id\": \"123\", \"status\": \"INVALID\"}</tool>";
        String result = executor.executeTools(input);

        assertThat(result).contains("⚠");
        assertThat(result).contains("Ungültiger Status");
    }

    @Test
    void executeTools_createWorkflow_success() {
        Workflow created = Workflow.builder().id("wf-123").name("Test Workflow").build();
        when(workflowService.create(any())).thenReturn(created);

        String input = "<tool type=\"create_workflow\">{\"projectId\": \"p1\", \"name\": \"Test Workflow\"}</tool>";
        String result = executor.executeTools(input);

        assertThat(result).contains("✓");
        assertThat(result).contains("Test Workflow");
    }

    @Test
    void executeTools_createWorkflow_missingName_returnsError() {
        String input = "<tool type=\"create_workflow\">{\"projectId\": \"p1\"}</tool>";
        String result = executor.executeTools(input);

        assertThat(result).contains("⚠");
        assertThat(result).contains("Pflichtfeld");
    }

    @Test
    void executeTools_startWorkflow_success() {
        Workflow wf = Workflow.builder().id("wf-1").name("My WF").build();
        when(workflowService.findById("wf-1")).thenReturn(wf);
        WorkflowRun run = WorkflowRun.builder().id("run-12345678").build();
        when(workflowExecutor.execute(eq(wf), any())).thenReturn(run);

        String input = "<tool type=\"start_workflow\">{\"workflowId\": \"wf-1\"}</tool>";
        String result = executor.executeTools(input);

        assertThat(result).contains("✓");
        assertThat(result).contains("gestartet");
    }

    @Test
    void executeTools_cancelRun_success() {
        String input = "<tool type=\"cancel_run\">{\"runId\": \"run-12345678\"}</tool>";
        String result = executor.executeTools(input);

        assertThat(result).contains("✓");
        verify(workflowExecutor).cancelExecution("run-12345678");
    }

    @Test
    void executeTools_deleteWorkflow_success() {
        Workflow wf = Workflow.builder().id("wf-1").name("Old WF").build();
        when(workflowService.findById("wf-1")).thenReturn(wf);

        String input = "<tool type=\"delete_workflow\">{\"workflowId\": \"wf-1\"}</tool>";
        String result = executor.executeTools(input);

        assertThat(result).contains("✓");
        assertThat(result).contains("gelöscht");
        verify(workflowService).delete("wf-1");
    }

    @Test
    void executeTools_createChain_success() {
        WorkflowChain chain = WorkflowChain.builder().id("ch-1").name("My Chain").build();
        when(chainService.create(any())).thenReturn(chain);

        String input = "<tool type=\"create_chain\">{\"name\": \"My Chain\", \"entries\": [{\"workflowId\": \"wf-1\"}]}</tool>";
        String result = executor.executeTools(input);

        assertThat(result).contains("✓");
        assertThat(result).contains("Chain erstellt");
    }

    @Test
    void executeTools_sanitizesFullwidthUnicode() {
        Todo created = Todo.builder().id("12345678-abcd-1234").title("Unicode Test").build();
        when(todoService.create(any())).thenReturn(created);

        // Use fullwidth characters: ＂title＂： ＂Unicode Test＂
        String input = "<tool type=\"create_todo\">{\u201ctitle\u201d: \u201cUnicode Test\u201d\uff0c \u201cpriority\u201d: \u201cHIGH\u201d}</tool>";
        String result = executor.executeTools(input);

        assertThat(result).contains("✓");
    }

    @Test
    void executeTools_unknownToolType_returnsError() {
        String input = "<tool type=\"unknown_tool\">{}</tool>";
        String result = executor.executeTools(input);

        assertThat(result).contains("Unbekanntes Tool");
    }
}
