package com.automation.service.dialog;

import com.automation.entity.BlockDefinition;
import com.automation.mapper.WorkflowMapper;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.conversion.DialogWorkflowSuggestion;
import com.automation.service.memory.ConversationPreprocessor;
import com.automation.service.workflow.AgentInstructionsService;
import com.automation.service.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DialogConversionServiceTest {

    @Mock private ClaudeJsonlParser parser;
    @Mock private DialogSessionScanner scanner;
    @Mock private AiProviderRegistry aiProviderRegistry;
    @Mock private AiProvider aiProvider;
    @Mock private AgentInstructionsService agentInstructionsService;
    @Mock private WorkflowService workflowService;
    @Mock private WorkflowMapper workflowMapper;
    @Mock private ConversationPreprocessor conversationPreprocessor;

    private DialogConversionService service;

    @BeforeEach
    void setUp() {
        service = new DialogConversionService(
                parser, scanner, aiProviderRegistry, agentInstructionsService,
                workflowService, workflowMapper, conversationPreprocessor);

        lenient().when(aiProviderRegistry.getDefault()).thenReturn(aiProvider);
        lenient().when(agentInstructionsService.buildInstructionPrefix(any())).thenReturn("");
    }

    @Test
    void suggestFromSession_emptySession_returnsNotAvailable() throws IOException {
        var session = new ClaudeJsonlParser.ParsedSession("s1", "/project", Instant.now(), Instant.now(), List.of());
        when(parser.parse(any())).thenReturn(session);

        var result = service.suggestFromSession("/path/to/session.jsonl");

        assertThat(result.available()).isFalse();
        assertThat(result.notAvailableReason()).contains("keine verwertbaren Turns");
    }

    @Test
    void suggestFromSession_tooFewToolCalls_returnsNotAvailable() throws IOException {
        // One turn with one tool call (< 2 required)
        var msg = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "ls", null, null);
        var turn = new ClaudeJsonlParser.DialogTurn("t1", null, "assistant", Instant.now(), List.of(msg));
        var session = new ClaudeJsonlParser.ParsedSession("s1", "/project", Instant.now(), Instant.now(), List.of(turn));
        when(parser.parse(any())).thenReturn(session);

        var result = service.suggestFromSession("/path/to/session.jsonl");

        assertThat(result.available()).isFalse();
        assertThat(result.notAvailableReason()).contains("Zu wenige Tool-Calls");
    }

    @Test
    void suggestFromSession_bashToolCalls_fallbackToRuleBased() throws IOException {
        // AI fails, falls back to rule-based which creates shell blocks from Bash tool calls
        var msg1 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "npm test", null, null);
        var msg2 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "npm run build", null, null);
        var msg3 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "npm run lint", null, null);
        var turn = new ClaudeJsonlParser.DialogTurn("t1", null, "assistant", Instant.now(), List.of(msg1, msg2, msg3));
        var session = new ClaudeJsonlParser.ParsedSession("s1", "/project", Instant.now(), Instant.now(), List.of(turn));
        when(parser.parse(any())).thenReturn(session);

        // AI throws, so rule-based fallback kicks in
        when(aiProvider.reason(any())).thenThrow(new RuntimeException("AI unavailable"));

        var result = service.suggestFromSession("/path/to/session.jsonl");

        assertThat(result.available()).isTrue();
        assertThat(result.confidence()).isEqualTo(0.5); // rule-based confidence
        assertThat(result.blocks()).hasSize(3);
        assertThat(result.blocks().get(0).getType()).isEqualTo("shell");
        assertThat(result.blocks().get(0).getConfig().get("command")).isEqualTo("npm test");
    }

    @Test
    void suggestFromSession_internalCommands_filtered() throws IOException {
        // ls, cat, echo should be filtered out
        var msg1 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "ls -la", null, null);
        var msg2 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "cat file.txt", null, null);
        var msg3 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "mvn clean install", null, null);
        var msg4 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "mvn test", null, null);
        var turn = new ClaudeJsonlParser.DialogTurn("t1", null, "assistant", Instant.now(), List.of(msg1, msg2, msg3, msg4));
        var session = new ClaudeJsonlParser.ParsedSession("s1", "/project", Instant.now(), Instant.now(), List.of(turn));
        when(parser.parse(any())).thenReturn(session);
        when(aiProvider.reason(any())).thenThrow(new RuntimeException("AI unavailable"));

        var result = service.suggestFromSession("/path/to/session.jsonl");

        // Only mvn commands should remain (ls and cat are internal)
        assertThat(result.blocks()).hasSize(2);
        assertThat(result.blocks().get(0).getConfig().get("command")).isEqualTo("mvn clean install");
    }

    @Test
    void suggestFromSession_writeEditToolCalls_createSimpleBlocks() throws IOException {
        var msg1 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "npm test", null, null);
        var msg2 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Write", "src/App.tsx", null, null);
        var msg3 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "npm run build", null, null);
        var turn = new ClaudeJsonlParser.DialogTurn("t1", null, "assistant", Instant.now(), List.of(msg1, msg2, msg3));
        var session = new ClaudeJsonlParser.ParsedSession("s1", "/project", Instant.now(), Instant.now(), List.of(turn));
        when(parser.parse(any())).thenReturn(session);
        when(aiProvider.reason(any())).thenThrow(new RuntimeException("AI unavailable"));

        var result = service.suggestFromSession("/path/to/session.jsonl");

        assertThat(result.blocks()).hasSize(3);
        assertThat(result.blocks().get(0).getType()).isEqualTo("shell");
        assertThat(result.blocks().get(1).getType()).isEqualTo("simple");
        assertThat(result.blocks().get(2).getType()).isEqualTo("shell");
    }

    @Test
    void suggestFromSession_duplicateBlocks_deduplicated() throws IOException {
        // Two identical bash commands in sequence should be deduplicated
        var msg1 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "npm test", null, null);
        var msg2 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "npm test", null, null);
        var msg3 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "npm run build", null, null);
        var turn = new ClaudeJsonlParser.DialogTurn("t1", null, "assistant", Instant.now(), List.of(msg1, msg2, msg3));
        var session = new ClaudeJsonlParser.ParsedSession("s1", "/project", Instant.now(), Instant.now(), List.of(turn));
        when(parser.parse(any())).thenReturn(session);
        when(aiProvider.reason(any())).thenThrow(new RuntimeException("AI unavailable"));

        var result = service.suggestFromSession("/path/to/session.jsonl");

        // Should be deduplicated: npm test (once) + npm run build
        assertThat(result.blocks()).hasSize(2);
    }

    @Test
    void suggestFromSession_onlyInternalCommands_returnsNotAvailable() throws IOException {
        var msg1 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "ls", null, null);
        var msg2 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "echo hello", null, null);
        var msg3 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "pwd", null, null);
        var turn = new ClaudeJsonlParser.DialogTurn("t1", null, "assistant", Instant.now(), List.of(msg1, msg2, msg3));
        var session = new ClaudeJsonlParser.ParsedSession("s1", "/project", Instant.now(), Instant.now(), List.of(turn));
        when(parser.parse(any())).thenReturn(session);
        when(aiProvider.reason(any())).thenThrow(new RuntimeException("AI unavailable"));

        var result = service.suggestFromSession("/path/to/session.jsonl");

        assertThat(result.available()).isFalse();
        assertThat(result.notAvailableReason()).contains("Keine automatisierbaren Aktionen");
    }

    @Test
    void suggestFromSession_userTurns_ignored() throws IOException {
        // Only assistant turns should produce blocks
        var userMsg = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "npm test", null, null);
        var userTurn = new ClaudeJsonlParser.DialogTurn("t1", null, "user", Instant.now(), List.of(userMsg));
        var assistantMsg1 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "mvn test", null, null);
        var assistantMsg2 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "mvn package", null, null);
        var assistantTurn = new ClaudeJsonlParser.DialogTurn("t2", null, "assistant", Instant.now(), List.of(assistantMsg1, assistantMsg2));
        var session = new ClaudeJsonlParser.ParsedSession("s1", "/project", Instant.now(), Instant.now(), List.of(userTurn, assistantTurn));
        when(parser.parse(any())).thenReturn(session);
        when(aiProvider.reason(any())).thenThrow(new RuntimeException("AI unavailable"));

        var result = service.suggestFromSession("/path/to/session.jsonl");

        // Only assistant's blocks
        assertThat(result.blocks()).hasSize(2);
    }

    @Test
    void createFromSuggestion_notAvailable_throws() {
        var suggestion = DialogWorkflowSuggestion.notAvailable("No actions");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.createFromSuggestion(suggestion, "proj-1", "name"));
    }

    @Test
    void suggestFromSession_aiSuccess_returnsAiSuggestion() throws Exception {
        var msg1 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "npm test", null, null);
        var msg2 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "npm build", null, null);
        var turn = new ClaudeJsonlParser.DialogTurn("t1", null, "assistant", Instant.now(), List.of(msg1, msg2));
        var session = new ClaudeJsonlParser.ParsedSession("s1", "/project", Instant.now(), Instant.now(), List.of(turn));
        when(parser.parse(any())).thenReturn(session);
        lenient().when(conversationPreprocessor.processOutput(any())).thenAnswer(inv -> inv.getArgument(0));

        String aiResponse = """
                {
                  "suggestedName": "Build Pipeline",
                  "suggestedDescription": "Runs tests and build",
                  "confidence": 0.9,
                  "patternType": "scripting",
                  "blocks": [
                    {"type": "shell", "config": {"command": "npm test"}},
                    {"type": "shell", "config": {"command": "npm build"}}
                  ]
                }
                """;
        when(aiProvider.reason(any())).thenReturn(new AiProvider.ReasoningResult(aiResponse, null));

        var result = service.suggestFromSession("/path/to/session.jsonl");

        assertThat(result.available()).isTrue();
        assertThat(result.suggestedName()).isEqualTo("Build Pipeline");
        assertThat(result.confidence()).isEqualTo(0.9);
        assertThat(result.blocks()).hasSize(2);
    }

    /** The prompt names the block types as the executors do — it said "forEach" (TODO cf8db6f8). */
    @Test
    void suggestFromSession_promptCarriesTheBlockSpecifications() throws IOException {
        var msg1 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "npm test", null, null);
        var msg2 = new ClaudeJsonlParser.DialogMessage("tool_use", null, "Bash", "npm build", null, null);
        var turn = new ClaudeJsonlParser.DialogTurn("t1", null, "assistant", Instant.now(), List.of(msg1, msg2));
        when(parser.parse(any())).thenReturn(
                new ClaudeJsonlParser.ParsedSession("s1", "/project", Instant.now(), Instant.now(), List.of(turn)));
        lenient().when(conversationPreprocessor.processOutput(any())).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<AiProvider.ReasoningRequest> request = ArgumentCaptor.forClass(AiProvider.ReasoningRequest.class);
        when(aiProvider.reason(request.capture())).thenThrow(new RuntimeException("AI unavailable"));

        service.suggestFromSession("/path/to/session.jsonl");

        assertThat(request.getValue().prompt()).contains("[Bash] npm test")
                .contains("**Block type: shell**", "**Block type: retry**", "**Block type: foreach**")
                .contains("\"foreach\" NUR wenn").doesNotContain("forEach");
    }
}
