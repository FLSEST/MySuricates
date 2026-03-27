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
import com.automation.util.JsonRepairUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Analysiert Claude-Code-JSONL-Sessions und wandelt sie in Workflow-Suggestions um.
 * Nutzt KI-Analyse mit rule-based Fallback.
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

    // ConversationPreprocessor ist StepResult-basiert, wir brauchen nur processOutput
    private final ConversationPreprocessor conversationPreprocessor;

    /**
     * Analysiert eine JSONL-Session und liefert eine Workflow-Suggestion.
     * Fällt auf rule-based zurück wenn KI-Analyse fehlschlägt.
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
     * Erstellt einen Workflow aus einer bestätigten Suggestion.
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

        var created = workflowService.create(request);
        return workflowMapper.toResponse(created);
    }

    @SuppressWarnings("unchecked")
    private DialogWorkflowSuggestion suggestAI(ClaudeJsonlParser.ParsedSession session,
                                                String filePath) throws Exception {
        String compressed = buildCompressedTranscript(session);
        String systemPrompt = agentInstructionsService.buildInstructionPrefix(null);

        String prompt = """
                Analysiere diese Claude Code Session und extrahiere einen wiederverwendbaren MySuricates-Workflow.

                SESSION METADATEN:
                - Projekt: %s
                - Turns: %d

                SESSION-INHALT:
                %s

                Identifiziere das KERN-MUSTER — die Abfolge von Befehlen und Entscheidungen, die sich lohnt zu automatisieren.

                Ignoriere: exploratives Lesen von Dateien, fehlgeschlagene Versuche, Debugging-Sackgassen.
                Behalte: erfolgreiche Befehle, Datei-Änderungen mit Ergebnis, logischer Ablauf.

                Antworte NUR mit einem gültigen JSON-Objekt:
                {
                  "suggestedName": "prägnanter Workflow-Name",
                  "suggestedDescription": "Kurze Beschreibung was dieser Workflow tut",
                  "confidence": 0.85,
                  "patternType": "scripting|file_editing|research|mixed",
                  "blocks": [
                    {"type": "shell", "config": {"command": "...", "container_image": "debian:bookworm-slim", "on_failure": "stop"}},
                    {"type": "simple", "config": {"command": "Analysiere die Ausgabe und erstelle einen Report", "on_failure": "stop"}}
                  ]
                }

                Block-Regeln:
                - "shell" für deterministische Bash-Befehle (immer container_image angeben)
                - "simple" für KI-Reasoning-Aufgaben und Textgenerierung
                - "retry" für Befehle die transient fehlschlagen können (max_retries: 3)
                - "forEach" NUR wenn die Session klar über eine Liste iteriert
                - Verwandte Befehle zusammenfassen wenn sie eine logische Einheit bilden
                - confidence: 0.9 = klares Scripting-Muster, 0.6 = gemischt, 0.4 = unklar
                - Nur gültiges JSON, keine Erklärungen
                """.formatted(session.projectPath(), session.turns().size(), compressed);

        AiProvider.ReasoningResult aiResult = aiProviderRegistry.getDefault().reason(
                new AiProvider.ReasoningRequest(prompt, systemPrompt));
        String response = aiResult.text();

        String json = extractJson(response);
        Map<String, Object> parsed = JsonRepairUtil.parseWithRepair(json, new TypeReference<>() {});

        String sugName = (String) parsed.getOrDefault("suggestedName", "Dialog: " + truncate(session.projectPath(), 40));
        String sugDesc = (String) parsed.getOrDefault("suggestedDescription", "");
        double confidence = parsed.get("confidence") instanceof Number n ? n.doubleValue() : 0.5;
        String patternType = (String) parsed.getOrDefault("patternType", "mixed");

        List<BlockDefinition> blocks = parseBlocks(parsed.get("blocks"));
        if (blocks.isEmpty()) {
            return suggestRuleBased(session, filePath);
        }

        long toolCallCount = countToolCalls(session);
        return new DialogWorkflowSuggestion(
                sugName, sugDesc, blocks,
                session.sessionId(), filePath, session.projectPath(),
                confidence, patternType, null, (int) toolCallCount, true
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

                // Bash-Calls → shell-Block
                if ("Bash".equals(toolName)) {
                    String cmd = msg.toolInput() != null ? msg.toolInput() : "";
                    // Interne Hilfs-Commands überspringen
                    if (isInternalCommand(cmd)) continue;

                    Map<String, Object> config = new LinkedHashMap<>();
                    config.put("command", cmd);
                    config.put("container_image", "debian:bookworm-slim");
                    config.put("on_failure", "stop");
                    blocks.add(BlockDefinition.builder().type("shell").config(config).build());
                }
                // Andere bedeutende Tool-Calls → simple-Block als Platzhalter
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

        // Duplikate entfernen (identische aufeinanderfolgende Blöcke)
        blocks = deduplicate(blocks);

        String name = "Dialog: " + truncate(session.projectPath(), 50);
        return new DialogWorkflowSuggestion(
                name, "Aus Claude-Code-Session generiert (rule-based)", blocks,
                session.sessionId(), filePath, session.projectPath(),
                0.5, "mixed", null, toolCallsAnalyzed, true
        );
    }

    /**
     * Erstellt einen komprimierten Transkript-String aus der Session für den KI-Prompt.
     */
    private String buildCompressedTranscript(ClaudeJsonlParser.ParsedSession session) {
        StringBuilder sb = new StringBuilder();
        int maxLength = 8000; // Tokens-Budget für Kontext

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
                        // Meta-Commentary überspringen
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

    @SuppressWarnings("unchecked")
    private List<BlockDefinition> parseBlocks(Object blocksObj) {
        List<BlockDefinition> blocks = new ArrayList<>();
        if (!(blocksObj instanceof List<?> blockList)) return blocks;

        for (Object bObj : blockList) {
            if (!(bObj instanceof Map<?, ?> bMap)) continue;
            Map<String, Object> bTyped = (Map<String, Object>) bMap;
            String type = (String) bTyped.getOrDefault("type", "simple");
            Map<String, Object> config = bTyped.get("config") instanceof Map<?, ?> c
                    ? (Map<String, Object>) c
                    : new LinkedHashMap<>();
            blocks.add(BlockDefinition.builder().type(type).config(config).build());
        }
        return blocks;
    }

    private long countToolCalls(ClaudeJsonlParser.ParsedSession session) {
        return session.turns().stream()
                .filter(t -> "assistant".equals(t.role()))
                .flatMap(t -> t.messages().stream())
                .filter(m -> "tool_use".equals(m.type()))
                .count();
    }

    private List<BlockDefinition> deduplicate(List<BlockDefinition> blocks) {
        List<BlockDefinition> result = new ArrayList<>();
        BlockDefinition prev = null;
        for (BlockDefinition b : blocks) {
            if (prev == null || !b.getType().equals(prev.getType())
                    || !Objects.equals(b.getConfig(), prev.getConfig())) {
                result.add(b);
            }
            prev = b;
        }
        return result;
    }

    private boolean isInternalCommand(String cmd) {
        if (cmd == null || cmd.isBlank()) return true;
        // Typische interne/explorative Commands die keinen Wert als Workflow-Block haben
        return cmd.startsWith("ls ") || cmd.equals("ls") || cmd.startsWith("cat ")
                || cmd.startsWith("echo ") || cmd.startsWith("pwd")
                || cmd.contains("| head ") || cmd.contains("| tail ");
    }

    private boolean isMetaCommentary(String text) {
        if (text == null) return true;
        String lower = text.toLowerCase();
        return lower.startsWith("let me") || lower.startsWith("i'll now")
                || lower.startsWith("i'm going to") || lower.startsWith("i can see")
                || lower.startsWith("i notice") || lower.startsWith("looking at");
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return text;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
