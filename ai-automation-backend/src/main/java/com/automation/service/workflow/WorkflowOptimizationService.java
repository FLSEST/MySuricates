package com.automation.service.workflow;

import com.automation.entity.Workflow;
import com.automation.service.ai.HostClaudeService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowOptimizationService {

    private final WorkflowService workflowService;
    private final HostClaudeService hostClaude;
    private final ObjectMapper objectMapper;

    public Map<String, Object> optimizeWorkflow(String workflowId) {
        Workflow workflow = workflowService.findById(workflowId);

        String blocksJson;
        try {
            blocksJson = objectMapper.writeValueAsString(workflow.getBlocks());
        } catch (Exception e) {
            blocksJson = workflow.getBlocks().toString();
        }

        String prompt = buildOptimizationPrompt(
                workflow.getName(),
                workflow.getDescription(),
                workflow.getInstructions(),
                blocksJson
        );

        try {
            String response = hostClaude.reason(prompt);
            return parseResponse(response);
        } catch (Exception e) {
            log.error("Workflow optimization failed for {}: {}", workflowId, e.getMessage());
            return Map.of(
                    "error", true,
                    "message", "Optimization failed: " + e.getMessage()
            );
        }
    }

    private String buildOptimizationPrompt(String name, String description, String instructions, String blocksJson) {
        return """
                Du bist ein Workflow-Optimierer fuer die MySuricates-Plattform.

                ## Plattform-Kontext
                - Workflows bestehen aus Blocks die in Docker-Containern via Claude CLI ausgefuehrt werden
                - Verfuegbare Block-Typen: simple, retry, conditional, foreach, while, agentic, approval, sub-workflow
                - Variablen: {{ variable }} werden vor Ausfuehrung durch Nutzereingaben ersetzt
                - {{ previous_output }} enthaelt den Output des vorherigen Blocks

                ## Block-Typ Referenz
                - simple: { "command": "..." } — Einzelner Befehl/Task
                - retry: { "command": "...", "max_retries": N, "delay": S } — Wiederholung bei Fehler
                - conditional: { "condition": "...", "then_command": "...", "else_command": "..." }
                - foreach: { "items_source": "command|list", "items_command": "...", "command": "..." }
                - while: { "condition_command": "...", "command": "...", "max_iterations": N }
                - agentic: { "goal": "...", "success_criteria": "...", "max_iterations": N }
                - approval: { "question": "...", "options": [...] }
                - sub-workflow: { "workflow_id": "..." }

                ## Dein Auftrag
                Analysiere den folgenden Workflow und optimiere ihn. Beachte:
                1. DEFENSIV: Aendere nur was eine klare Verbesserung ist
                2. VERIFIZIERE: Stelle sicher dass {{ Variablen }} erhalten bleiben
                3. ERKLAERE: Begruende jede Aenderung
                4. ERHALTE: Behalte die Kernlogik bei, aendere nicht den Zweck

                ## Aktueller Workflow
                Name: %s
                Beschreibung: %s
                Instructions: %s
                Blocks: %s

                ## Antwort-Format (NUR valides JSON)
                {
                  "suggestedName": "...",
                  "suggestedDescription": "...",
                  "suggestedInstructions": "...",
                  "blocks": [...],
                  "changes": [
                    { "type": "modified|added|removed|reordered", "description": "Was und warum" }
                  ]
                }
                """.formatted(
                name != null ? name : "",
                description != null ? description : "",
                instructions != null ? instructions : "",
                blocksJson
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResponse(String response) {
        // Try to extract JSON from the response
        String json = response.trim();

        // Handle markdown code blocks
        if (json.contains("```json")) {
            int start = json.indexOf("```json") + 7;
            int end = json.indexOf("```", start);
            if (end > start) {
                json = json.substring(start, end).trim();
            }
        } else if (json.contains("```")) {
            int start = json.indexOf("```") + 3;
            int end = json.indexOf("```", start);
            if (end > start) {
                json = json.substring(start, end).trim();
            }
        }

        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Could not parse optimization response as JSON: {}", e.getMessage());
            return Map.of(
                    "suggestedName", "",
                    "suggestedDescription", "",
                    "suggestedInstructions", "",
                    "blocks", List.of(),
                    "changes", List.of(Map.of("type", "error", "description", "Could not parse AI response")),
                    "rawResponse", response
            );
        }
    }
}
