package com.automation.service.assistant;

import com.automation.entity.Todo;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static com.automation.service.assistant.AssistantToolPayloads.shorten;

/**
 * Names a proposed assistant action for its confirmation card (TODO 4e152029): resolves the ids in
 * the payload to human names (workflow name, the run's workflow name, TODO title) and returns them
 * as {@link PendingToolLabel#subject()} plus small {@link PendingToolLabel#params()}, so the
 * frontend can phrase the sentence in the UI language. The English {@code description} stays as
 * fallback for older clients and unknown types.
 *
 * <p>Lookups are single-row, read-only projections outside any caller transaction (no slow work
 * holds the one SQLite connection). An id that cannot be resolved falls back to its shortened form
 * and is marked with {@code params.ref = "id"}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingToolDescriber {

    static final String REF = "ref";
    static final String REF_ID = "id";

    private final ObjectMapper objectMapper;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final TodoRepository todoRepository;

    /** Builds the label for a tool proposal; never throws. */
    public PendingToolLabel describe(String type, String payloadJson) {
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(payloadJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("describe failed for tool type={} — falling back to type label", type, e);
            return PendingToolLabel.of(type);
        }
        if (payload == null) return PendingToolLabel.of(type);
        return switch (type) {
            case "create_todo" -> {
                String title = text(payload.get("title"));
                String priority = upper(payload.get("priority"), "MEDIUM");
                yield new PendingToolLabel("Create TODO: \"" + orQ(title) + "\" (priority: " + priority + ")",
                        title, Map.of("priority", priority));
            }
            case "update_todo_status" -> {
                String status = upper(payload.get("status"), "?");
                Ref todo = resolve(payload.get("id"), () -> todoTitle(payload.get("id")));
                String description = todo.resolved()
                        ? "Change status of TODO \"" + todo.subject() + "\" → " + status
                        : "Change TODO status (ID: " + todo.subject() + ") → " + status;
                yield label(description, todo, Map.of("status", status));
            }
            case "create_workflow" -> named("Create workflow", payload.get("name"));
            case "create_chain" -> named("Create chain", payload.get("name"));
            case "update_workflow" -> workflowAction("Update workflow", payload.get("workflowId"));
            case "delete_workflow" -> workflowAction("DELETE workflow", payload.get("workflowId"));
            case "start_workflow" -> workflowAction("Start workflow", payload.get("workflowId"));
            case "cancel_run" -> {
                Ref run = resolve(payload.get("runId"), () -> runWorkflowName(payload.get("runId")));
                yield label(run.resolved() ? "Cancel run of \"" + run.subject() + "\""
                        : "Cancel run (ID: " + run.subject() + ")", run, Map.of());
            }
            default -> PendingToolLabel.of(type);
        };
    }

    private PendingToolLabel named(String verb, Object rawName) {
        String name = text(rawName);
        return new PendingToolLabel(verb + ": \"" + orQ(name) + "\"", name, Map.of());
    }

    private PendingToolLabel workflowAction(String verb, Object workflowId) {
        Ref wf = resolve(workflowId, () -> workflowName(workflowId));
        return label(wf.resolved() ? verb + " \"" + wf.subject() + "\"" : verb + " (ID: " + wf.subject() + ")",
                wf, Map.of());
    }

    private static PendingToolLabel label(String description, Ref ref, Map<String, String> params) {
        Map<String, String> all = new LinkedHashMap<>(params);
        if (!ref.resolved()) all.put(REF, REF_ID);
        return new PendingToolLabel(description, ref.subject(), all);
    }

    /** Resolved name, or the shortened id when the lookup finds nothing or fails. */
    private Ref resolve(Object id, Supplier<Optional<String>> lookup) {
        if (text(id) != null) {
            try {
                Optional<String> name = lookup.get().filter(n -> !n.isBlank());
                if (name.isPresent()) return new Ref(name.get(), true);
            } catch (Exception e) {
                log.debug("Pending-tool name lookup failed for id={}", id, e);
            }
        }
        return new Ref(shorten(id), false);
    }

    private Optional<String> workflowName(Object id) {
        List<Object[]> rows = workflowRepository.findIdAndNameByIdIn(List.of(id.toString()));
        return rows.stream().findFirst().map(r -> text(r[1]));
    }

    private Optional<String> runWorkflowName(Object runId) {
        List<Object[]> rows = workflowRunRepository.findWorkflowRefsByRunIds(List.of(runId.toString()));
        if (rows.isEmpty()) return Optional.empty();
        String snapshot = text(rows.get(0)[2]);
        if (snapshot != null) return Optional.of(snapshot);
        Object workflowId = rows.get(0)[1];
        return workflowId == null ? Optional.empty() : workflowName(workflowId);
    }

    private Optional<String> todoTitle(Object id) {
        return todoRepository.findById(id.toString()).map(Todo::getTitle);
    }

    private static String text(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String upper(Object v, String fallback) {
        String s = text(v);
        return s == null ? fallback : s.toUpperCase(Locale.ROOT);
    }

    private static String orQ(String s) {
        return s == null ? "?" : s;
    }

    private record Ref(String subject, boolean resolved) {}
}
