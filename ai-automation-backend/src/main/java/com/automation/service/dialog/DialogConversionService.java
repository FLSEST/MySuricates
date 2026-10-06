package com.automation.service.dialog;

import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.response.WorkflowResponse;
import com.automation.entity.BlockDefinition;
import com.automation.mapper.WorkflowMapper;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.conversion.DialogWorkflowSuggestion;
import com.automation.service.memory.ConversationPreprocessor;
import com.automation.service.workflow.AgentInstructionsService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.analyzer.GeneratedWorkflowCheck;
import com.automation.util.BlockJsonReader;
import com.automation.util.JsonRepairUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static com.automation.service.dialog.DialogTextRules.deduplicate;
import static com.automation.service.dialog.DialogTextRules.extractJson;
import static com.automation.service.dialog.DialogTextRules.isInternalCommand;
import static com.automation.service.dialog.DialogTextRules.isMetaCommentary;
import static com.automation.service.dialog.DialogTextRules.truncate;

/**
 * Analyzes Claude Code JSONL sessions and turns them into workflow suggestions.
 * Uses AI analysis with a rule-based fallback.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DialogConversionService {

    private final ClaudeJsonlParser parser;
    private final DialogSessionScanner scanner;
    private final AiProviderRegistry aiProviderRegistry;
    private final AgentInstructionsService agentInstructionsService;
    private final WorkflowService workflowService;
    private final WorkflowMapper workflowMapper;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ConversationPreprocessor is StepResult-based, we only need processOutput
    private final ConversationPreprocessor conversationPreprocessor;
    private final GeneratedWorkflowCheck buildCheck;

    /**
     * Analyzes a JSONL session and returns a workflow suggestion.
     * Falls back to rule-based if the AI analysis fails.
     */
    public DialogWorkflowSuggestion suggestFromSession(String absoluteJsonlPath) throws IOException {
        ClaudeJsonlParser.ParsedSession session = parser.parse(Path.of(absoluteJsonlPath));

        if (session.turns().isEmpty()) {
            return DialogWorkflowSuggestion.notAvailable("Session enthält keine verwertbaren Turns");
        }

        long toolCallCount = countToolCalls(session);
        if (toolCallCount < 2) {
            return DialogWorkflowSuggestion.notAvailable("Zu wenige Tool-Calls für eine sinnvolle Konvertierung");
        }

        try {
            return suggestAI(session, absoluteJsonlPath);
        } catch (Exception e) {
            log.debug("KI-Analyse fehlgeschlagen, verwende rule-based: {}", e.getMessage());
            return suggestRuleBased(session, absoluteJsonlPath);
        }
    }

    /**
     * Creates a workflow from a confirmed suggestion.
     */
    public WorkflowResponse createFromSuggestion(DialogWorkflowSuggestion suggestion,
                                                   String projectId, String name) {
        if (!suggestion.available()) {
            throw new IllegalStateException("Suggestion nicht verfügbar: " + suggestion.notAvailableReason());
        }

        String workflowName = name != null && !name.isBlank() ? name : suggestion.suggestedName();

        CreateWorkflowRequest request = new CreateWorkflowRequest(
                projectId,
                workflowName,
                suggestion.suggestedDescription(),
                null,
                suggestion.blocks(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        var created = workflowService.create(request, com.automation.entity.ChangeSource.DIALOG);
        // The suggestion comes back from the client and may have been edited: check the saved version.
        buildCheck.reportRemaining(created.getId(), created.getName(), "Dialog-Import", buildCheck.findings(created));
        return workflowMapper.toResponse(created);
    }

    @SuppressWarnings("unchecked")
    private DialogWorkflowSuggestion suggestAI(ClaudeJsonlParser.ParsedSession session,
                                                String filePath) throws Exception {
        String compressed = buildCompressedTranscript(session);
        String systemPrompt = agentInstructionsService.buildInstructionPrefix(null);

        String prompt = DialogConversionPrompt.build(session.projectPath(), session.turns().size(), compressed);

        AiProvider provider = aiProviderRegistry.getDefault();
        AiProvider.ReasoningResult aiResult = provider.reason(
                new AiProvider.ReasoningRequest(prompt, systemPrompt));
        String response = aiResult.text();

        String json = extractJson(response);
        Map<String, Object> parsed = JsonRepairUtil.parseWithRepair(json, new TypeReference<>() {});

        String sugName = (String) parsed.getOrDefault("suggestedName", "Dialog: " + truncate(session.projectPath(), 40));
        String sugDesc = (String) parsed.getOrDefault("suggestedDescription", "");
        double confidence = parsed.get("confidence") instanceof Number n ? n.doubleValue() : 0.5;
        String patternType = (String) parsed.getOrDefault("patternType", "mixed");

        List<BlockDefinition> blocks = BlockJsonReader.fromMapList(parsed.get("blocks"));
        if (blocks.isEmpty()) {
            return suggestRuleBased(session, filePath);
        }

        // Build-error check (TODO 326d5987): for HIGH exactly one correction round with the same provider and
        // system prompt; remaining findings are part of the suggestion the user sees before creating.
        GeneratedWorkflowCheck.Correction check = buildCheck.correctOnce(blocks, null,
                correctionPrompt -> provider.reason(new AiProvider.ReasoningRequest(correctionPrompt, systemPrompt)).text());

        long toolCallCount = countToolCalls(session);
        return new DialogWorkflowSuggestion(
                sugName, sugDesc, check.blocks(),
                session.sessionId(), filePath, session.projectPath(),
                confidence, patternType, null, (int) toolCallCount, true,
                GeneratedWorkflowCheck.summaryLines(check.remaining()),
                GeneratedWorkflowCheck.summaryLines(check.remaining(), "en")
        );
    }

    private DialogWorkflowSuggestion suggestRuleBased(ClaudeJsonlParser.ParsedSession session,
                                                       String filePath) {
        List<BlockDefinition> blocks = new ArrayList<>();
        int toolCallsAnalyzed = 0;

        for (ClaudeJsonlParser.DialogTurn turn : session.turns()) {
            if (!"assistant".equals(turn.role())) continue;

            for (ClaudeJsonlParser.DialogMessage msg : turn.messages()) {
                if (!"tool_use".equals(msg.type())) continue;

                String toolName = msg.toolName();
                if (toolName == null) continue;

                toolCallsAnalyzed++;

                // Bash calls → shell block
                if ("Bash".equals(toolName)) {
                    String cmd = msg.toolInput() != null ? msg.toolInput() : "";
                    // Skip internal helper commands
                    if (isInternalCommand(cmd)) continue;

                    Map<String, Object> config = new LinkedHashMap<>();
                    config.put("command", cmd);
                    config.put("container_image", "debian:bookworm-slim");
                    config.put("on_failure", "stop");
                    blocks.add(BlockDefinition.builder().type("shell").config(config).build());
                }
                // Other significant tool calls → simple block as placeholder
                else if ("Write".equals(toolName) || "Edit".equals(toolName)) {
                    Map<String, Object> config = new LinkedHashMap<>();
                    config.put("command", "Bearbeite Datei: " + (msg.toolInput() != null ? msg.toolInput() : ""));
                    config.put("on_failure", "stop");
                    blocks.add(BlockDefinition.builder().type("simple").config(config).build());
                }
            }
        }

        if (blocks.isEmpty()) {
            return DialogWorkflowSuggestion.notAvailable("Keine automatisierbaren Aktionen gefunden");
        }

        // Remove duplicates (identical consecutive blocks)
        blocks = deduplicate(blocks);

        String name = "Dialog: " + truncate(session.projectPath(), 50);
        // Rule-based there is no AI that could correct: only report.
        var findings = buildCheck.findings(blocks, null);
        return new DialogWorkflowSuggestion(
                name, "Aus Claude-Code-Session generiert (rule-based)", blocks,
                session.sessionId(), filePath, session.projectPath(),
                0.5, "mixed", null, toolCallsAnalyzed, true,
                GeneratedWorkflowCheck.summaryLines(findings),
                GeneratedWorkflowCheck.summaryLines(findings, "en")
        );
    }

    /**
     * Creates a compressed transcript string from the session for the AI prompt.
     */
    private String buildCompressedTranscript(ClaudeJsonlParser.ParsedSession session) {
        StringBuilder sb = new StringBuilder();
        int maxLength = 8000; // token budget for context

        for (ClaudeJsonlParser.DialogTurn turn : session.turns()) {
            if (sb.length() > maxLength) {
                sb.append("\n[... weitere Turns gekürzt ...]\n");
                break;
            }

            String roleLabel = "user".equals(turn.role()) ? "USER" : "ASSISTANT";
            sb.append("\n[").append(roleLabel).append("]\n");

            for (ClaudeJsonlParser.DialogMessage msg : turn.messages()) {
                switch (msg.type()) {
                    case "text" -> {
                        String text = truncate(msg.text(), 300);
                        // Skip meta commentary
                        if (!isMetaCommentary(text)) {
                            sb.append(text).append("\n");
                        }
                    }
                    case "tool_use" -> {
                        String input = msg.toolInput() != null ? msg.toolInput() : "";
                        String truncInput = truncate(input, 200);
                        sb.append("[").append(msg.toolName()).append("] ").append(truncInput).append("\n");
                    }
                    case "tool_result" -> {
                        if (msg.resultText() != null) {
                            String processed = conversationPreprocessor.processOutput(msg.resultText());
                            String truncResult = truncate(processed, 300);
                            sb.append("→ ").append(truncResult).append("\n");
                        }
                    }
                }
            }
        }

        return sb.toString().trim();
    }

    private long countToolCalls(ClaudeJsonlParser.ParsedSession session) {
        return session.turns().stream()
                .filter(t -> "assistant".equals(t.role()))
                .flatMap(t -> t.messages().stream())
                .filter(m -> "tool_use".equals(m.type()))
                .count();
    }
}
