package com.automation.executor.blocks;

import com.automation.dto.request.UpdateTodoRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Todo;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.service.todo.TodoService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Native todo-update block (TODO 9f6d7f14): aktualisiert ein TODO direkt über
 * den TodoService — ersetzt REST-Self-Calls (PUT /api/v1/todos/{id}).
 *
 * Config: id (Pflicht, Template-fähig), optional title/description/priority/tags —
 * nur gesetzte, nicht leere Felder werden geändert (Null-Semantik von TodoService.update, 4a2edda6).
 * Statt id: updates – viele TODOs des Lauf-Projekts auf einmal, siehe {@link TodoBulkUpdates} (TODO cc0eb3ba).
 * Das Datum der Vermerke ist der Tag in der Zeitzone der Instanz ({@link Clock}-Bean), nicht UTC – ein
 * nächtlicher Lauf um 01:30 trüge sonst das Datum des Vortags (d6c0afe6).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TodoUpdateBlockExecutor implements BlockExecutor {

    private final TodoService todoService;
    private final Clock clock;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        StepResult result = TodoBlockSupport.startResult(context);
        try {
            // Leere Texte gelten als nicht gesetzt – wie im Analyzer (requiredOneOf in blockConfigSchema.json)
            if (isSet(config.get("updates"))) {
                if (isSet(config.get("id"))) {
                    throw new IllegalArgumentException("todo-update: entweder 'id' oder 'updates', nicht beides");
                }
                String projectId = Objects.toString(context.getVariable("project_id"), "");
                if (projectId.isBlank()) {
                    throw new IllegalArgumentException("todo-update mit 'updates' braucht ein Projekt");
                }
                Object raw = config.get("updates");
                String text = raw instanceof String ? TodoBlockSupport.resolved(context, raw) : TodoBulkUpdates.toJson(raw);
                List<JsonNode> entries = TodoBulkUpdates.parse(text);
                TodoBulkUpdates.Result bulk = TodoBulkUpdates.apply(entries, projectId,
                        Objects.toString(context.getVariable("workflow_name"), ""),
                        context.getWorkflowRunId(), todoService, LocalDate.now(clock));
                result.setInput("todo-update: " + entries.size() + " Einträge");
                result.setOutput(bulk.summary());
                result.setStatus(StepStatus.COMPLETED);
                return TodoBlockSupport.finishResult(result);
            }
            String id = TodoBlockSupport.resolved(context, config.get("id"));
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("todo-update requires 'id' in config");
            }

            UpdateTodoRequest request = new UpdateTodoRequest(
                    unlessBlank(TodoBlockSupport.resolved(context, config.get("title"))),
                    unlessBlank(TodoBlockSupport.resolved(context, config.get("description"))),
                    null,
                    config.containsKey("priority")
                            ? TodoBlockSupport.parsePriority(TodoBlockSupport.resolved(context, config.get("priority")), null)
                            : null,
                    TodoBlockSupport.parseTags(config.get("tags")),
                    null, null, null, null,
                    null,   // agentSessionRef: ein Workflow-Block hat keine Agenten-Konversation
                    null);

            Todo updated = todoService.update(id, request);

            result.setInput("todo-update: " + id);
            result.setOutput("✓ TODO aktualisiert: " + updated.getId() + " — " + updated.getTitle());
            result.setStatus(StepStatus.COMPLETED);
        } catch (Exception e) {
            log.warn("todo-update block failed: {}", e.getMessage());
            result.setOutput("todo-update fehlgeschlagen: " + e.getMessage());
            result.setStatus(StepStatus.FAILED);
        }
        return TodoBlockSupport.finishResult(result);
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("todo-update");
    }

    private static boolean isSet(Object value) {
        return value != null && !(value instanceof String text && text.isBlank());
    }

    /** Leer oder nur Leerraum heißt „nicht ändern“ – der Editor legt den Block mit leeren Feldern an (4a2edda6). */
    private static String unlessBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
