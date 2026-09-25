package com.automation.executor.blocks;

import com.automation.dto.request.UpdateTodoRequest;
import com.automation.entity.Todo;
import com.automation.mapper.TodoMapper;
import com.automation.service.todo.TodoService;
import com.automation.util.UntrustedText;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code todo-update} mit {@code updates} (TODO cc0eb3ba): viele TODOs des Lauf-Projekts in einem Schritt – Status,
 * Tags hinzu/weg, Vermerk, mit Bedingungen. Die Liste kommt meist aus einem Shell-Schritt
 * ({@code {{ block.<name>.output }}}); ist dessen ganze Ausgabe kein JSON, gilt ihre letzte nicht-leere Zeile.
 *
 * <p>Struktur-Fehler – kein JSON, keine Liste, unbekannter Schlüssel, mehr als {@value #MAX_ENTRIES} Einträge –
 * lassen den Schritt scheitern, bevor etwas geschrieben ist. Ein Eintrag, der nicht passt (ungültige ID oder
 * Werte, fremdes Projekt, Bedingung nicht erfüllt), wird mit Grund übersprungen.
 */
final class TodoBulkUpdates {

    static final int MAX_ENTRIES = 50;
    static final int MAX_TAGS = 10;
    static final int MAX_NOTE_CHARS = 1000;
    static final Set<String> KEYS =
            Set.of("id", "status", "add_tags", "remove_tags", "note", "require_tags", "require_status");
    static final Set<Todo.Status> STATUSES =
            EnumSet.of(Todo.Status.OPEN, Todo.Status.IN_PROGRESS, Todo.Status.DONE, Todo.Status.BACKLOG);

    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern TAG = Pattern.compile("^[A-Za-z0-9_:.\\-]{1,60}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private TodoBulkUpdates() {
    }

    record Result(int updated, int skipped, List<String> lines) {
        String summary() {
            StringBuilder sb = new StringBuilder("✓ " + updated + " aktualisiert, " + skipped + " übersprungen");
            lines.forEach(line -> sb.append('\n').append(line));
            return sb.toString();
        }
    }

    /** Eine Liste direkt aus der Config (statt String) als JSON-Text. */
    static String toJson(Object raw) {
        try {
            return JSON.writeValueAsString(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("updates ist keine JSON-Liste");
        }
    }

    /** Liest die Liste; {@link IllegalArgumentException} bei Struktur-Fehlern. */
    static List<JsonNode> parse(String raw) {
        String text = raw == null ? "" : raw.strip();
        JsonNode root = read(text);
        if (root == null || !root.isArray()) {
            JsonNode last = read(lastNonEmptyLine(text));
            if (last != null) root = last;
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("updates ist keine JSON-Liste");
        }
        if (root.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("updates hat " + root.size() + " Einträge, erlaubt sind " + MAX_ENTRIES);
        }
        List<JsonNode> entries = new ArrayList<>();
        for (JsonNode entry : root) {
            if (!entry.isObject()) {
                throw new IllegalArgumentException("updates: jeder Eintrag muss ein Objekt sein");
            }
            entry.fieldNames().forEachRemaining(key -> {
                if (!KEYS.contains(key)) {
                    throw new IllegalArgumentException("updates: unbekannter Schlüssel '" + key + "'");
                }
            });
            entries.add(entry);
        }
        return entries;
    }

    static Result apply(List<JsonNode> entries, String projectId, String workflowName, String runId,
                        TodoService todoService, LocalDate today) {
        int updated = 0;
        List<String> lines = new ArrayList<>();
        for (JsonNode entry : entries) {
            String id = entry.path("id").asText("");
            String problem = check(entry);
            if (problem == null) {
                problem = applyOne(entry, id, projectId, workflowName, runId, todoService, today);
            }
            if (problem == null) {
                updated++;
                lines.add("✓ " + shortId(id));
            } else {
                lines.add("– " + shortId(id) + ": " + problem);
            }
        }
        return new Result(updated, entries.size() - updated, lines);
    }

    private static String check(JsonNode entry) {
        if (!UUID.matcher(entry.path("id").asText("")).matches()) return "keine gültige ID";
        for (String key : List.of("status", "require_status")) {
            if (entry.has(key) && status(entry.path(key)) == null) return "unbekannter Status in " + key;
        }
        for (String key : List.of("add_tags", "remove_tags", "require_tags")) {
            JsonNode tags = entry.path(key);
            if (tags.isMissingNode() || tags.isNull()) continue;
            if (!tags.isArray() || tags.size() > MAX_TAGS) return key + " muss eine Liste mit höchstens " + MAX_TAGS + " Tags sein";
            for (JsonNode tag : tags) {
                if (!tag.isTextual() || !TAG.matcher(tag.asText()).matches()) return "ungültiges Tag in " + key;
            }
        }
        if (entry.has("note") && !entry.path("note").isTextual()) return "note muss Text sein";
        return null;
    }

    private static String applyOne(JsonNode entry, String id, String projectId, String workflowName, String runId,
                                   TodoService todoService, LocalDate today) {
        Todo todo;
        try {
            todo = todoService.findById(id);
        } catch (RuntimeException e) {
            return "nicht gefunden";
        }
        if (!projectId.equals(todo.getProjectId())) return "gehört zu einem anderen Projekt";
        Todo.Status requiredStatus = status(entry.path("require_status"));
        if (requiredStatus != null && todo.getStatus() != requiredStatus) return "Status ist " + todo.getStatus();
        List<String> tags = new ArrayList<>(TodoMapper.jsonToList(todo.getTags()));
        for (String required : strings(entry.path("require_tags"))) {
            if (!tags.contains(required)) return "Tag fehlt: " + required;
        }
        List<String> remove = strings(entry.path("remove_tags"));
        tags.removeIf(remove::contains);
        for (String add : strings(entry.path("add_tags"))) {
            if (!tags.contains(add)) tags.add(add);
        }
        String description = null;
        String note = entry.path("note").asText("").strip();
        if (!note.isEmpty()) {
            String name = workflowName == null || workflowName.isBlank() ? "Workflow" : workflowName;
            String line = "— " + today + " · " + name + " (Lauf " + shortId(runId) + "): "
                    + UntrustedText.inline(note, MAX_NOTE_CHARS);
            String current = todo.getDescription() == null ? "" : todo.getDescription().stripTrailing();
            description = current.isEmpty() ? line : current + "\n\n" + line;
        }
        todoService.update(id, new UpdateTodoRequest(null, description, status(entry.path("status")), null, tags,
                null, null, null, null, null));
        return null;
    }

    private static Todo.Status status(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        try {
            Todo.Status status = Todo.Status.valueOf(node.asText("").trim().toUpperCase(Locale.ROOT));
            return STATUSES.contains(status) ? status : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static JsonNode read(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return JSON.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String lastNonEmptyLine(String text) {
        String[] lines = text.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) return lines[i].strip();
        }
        return null;
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.substring(0, Math.min(8, id.length()));
    }
}
