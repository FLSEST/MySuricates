package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.service.ai.HostClaudeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowOptimizationServiceTest {

    @Mock
    private WorkflowService workflowService;

    @Mock
    private HostClaudeService hostClaude;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private WorkflowOptimizationService optimizationService;

    private Workflow testWorkflow;

    @BeforeEach
    void setUp() {
        testWorkflow = Workflow.builder()
                .id("wf-1")
                .name("Test Workflow")
                .description("A test workflow")
                .instructions("Do the thing")
                .blocks(List.of(
                        BlockDefinition.builder()
                                .type("simple")
                                .config(Map.of("command", "echo hello"))
                                .build()
                ))
                .build();
    }

    @Test
    void optimizeWorkflow_validJsonResponse_returnsParsedResult() throws IOException {
        when(workflowService.findById("wf-1")).thenReturn(testWorkflow);

        String jsonResponse = """
                {
                  "suggestedName": "Optimized Workflow",
                  "suggestedDescription": "Better description",
                  "suggestedInstructions": "New instructions",
                  "blocks": [],
                  "changes": [{"type": "modified", "description": "Improved prompt"}]
                }
                """;
        when(hostClaude.reason(anyString())).thenReturn(jsonResponse);

        Map<String, Object> result = optimizationService.optimizeWorkflow("wf-1");

        assertThat(result).containsKey("suggestedName");
        assertThat(result.get("suggestedName")).isEqualTo("Optimized Workflow");
        assertThat(result).containsKey("changes");
    }

    @Test
    void optimizeWorkflow_markdownCodeBlock_extractsJson() throws IOException {
        when(workflowService.findById("wf-1")).thenReturn(testWorkflow);

        String response = """
                Here's the optimization:
                ```json
                {
                  "suggestedName": "Better Name",
                  "suggestedDescription": "desc",
                  "suggestedInstructions": "",
                  "blocks": [],
                  "changes": []
                }
                ```
                Hope that helps!
                """;
        when(hostClaude.reason(anyString())).thenReturn(response);

        Map<String, Object> result = optimizationService.optimizeWorkflow("wf-1");

        assertThat(result.get("suggestedName")).isEqualTo("Better Name");
    }

    @Test
    void optimizeWorkflow_plainCodeBlock_extractsJson() throws IOException {
        when(workflowService.findById("wf-1")).thenReturn(testWorkflow);

        String response = """
                ```
                {
                  "suggestedName": "Extracted",
                  "suggestedDescription": "",
                  "suggestedInstructions": "",
                  "blocks": [],
                  "changes": []
                }
                ```
                """;
        when(hostClaude.reason(anyString())).thenReturn(response);

        Map<String, Object> result = optimizationService.optimizeWorkflow("wf-1");

        assertThat(result.get("suggestedName")).isEqualTo("Extracted");
    }

    @Test
    void optimizeWorkflow_invalidJsonResponse_returnsFallback() throws IOException {
        when(workflowService.findById("wf-1")).thenReturn(testWorkflow);
        when(hostClaude.reason(anyString())).thenReturn("This is not JSON at all");

        Map<String, Object> result = optimizationService.optimizeWorkflow("wf-1");

        assertThat(result).containsKey("rawResponse");
        assertThat(result.get("rawResponse")).isEqualTo("This is not JSON at all");
        assertThat(result).containsKey("suggestedName");
    }

    @Test
    void optimizeWorkflow_claudeThrows_returnsError() throws IOException {
        when(workflowService.findById("wf-1")).thenReturn(testWorkflow);
        when(hostClaude.reason(anyString())).thenThrow(new IOException("Connection failed"));

        Map<String, Object> result = optimizationService.optimizeWorkflow("wf-1");

        assertThat(result.get("error")).isEqualTo(true);
        assertThat((String) result.get("message")).contains("Connection failed");
    }

    @Test
    void optimizeWorkflow_nullInstructions_handledGracefully() throws IOException {
        testWorkflow.setInstructions(null);
        when(workflowService.findById("wf-1")).thenReturn(testWorkflow);

        String jsonResponse = """
                {"suggestedName":"OK","suggestedDescription":"","suggestedInstructions":"","blocks":[],"changes":[]}
                """;
        when(hostClaude.reason(anyString())).thenReturn(jsonResponse);

        Map<String, Object> result = optimizationService.optimizeWorkflow("wf-1");

        assertThat(result.get("suggestedName")).isEqualTo("OK");
    }

    @Test
    void optimizeWorkflow_promptContainsWorkflowDetails() throws IOException {
        when(workflowService.findById("wf-1")).thenReturn(testWorkflow);

        String jsonResponse = """
                {"suggestedName":"","suggestedDescription":"","suggestedInstructions":"","blocks":[],"changes":[]}
                """;
        when(hostClaude.reason(contains("Test Workflow"))).thenReturn(jsonResponse);

        optimizationService.optimizeWorkflow("wf-1");

        // Verified by the contains() matcher - prompt includes workflow name
    }
}
