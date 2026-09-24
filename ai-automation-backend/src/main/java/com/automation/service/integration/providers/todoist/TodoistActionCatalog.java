package com.automation.service.integration.providers.todoist;

import com.automation.service.integration.IntegrationAction;

import java.util.List;
import java.util.Map;

/**
 * Static catalog of the Todoist actions (API v1). Kept apart from {@link TodoistProvider},
 * which owns request building and execution. Todoist API tokens carry no scopes, so no action
 * lists required scopes.
 */
final class TodoistActionCatalog {

    private TodoistActionCatalog() {}

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static final Map<String, Object> CURSOR = str("Cursor aus 'next_cursor' der vorigen Seite");
    private static final Map<String, Object> LIMIT = Map.of("type", "integer", "default", 50,
            "description", "Einträge pro Seite (1–200)");
    private static final Map<String, Object> PRIORITY = Map.of("type", "integer",
            "description", "Priorität 1 (normal) bis 4 (dringend, in den Apps „P1“)");
    private static final Map<String, Object> LABELS = Map.of("type", "string",
            "description", "Label-Namen (kommagetrennt oder als Liste)");

    static List<IntegrationAction> actions() {
        return List.of(
                new IntegrationAction("list_projects", "Projekte auflisten (seitenweise, 'next_cursor')",
                        Map.of("type", "object", "properties", Map.of("cursor", CURSOR, "limit", LIMIT))),
                new IntegrationAction("list_tasks",
                        "Offene Aufgaben auflisten — nach Projekt oder per Todoist-Filter (z.B. 'today | overdue'), nicht beides",
                        Map.of("type", "object", "properties", Map.of(
                                "project_id", str("Nur Aufgaben dieses Projekts"),
                                "filter", str("Todoist-Filterausdruck, z.B. 'today | overdue' oder '#Arbeit & p1'"),
                                "cursor", CURSOR,
                                "limit", LIMIT))),
                new IntegrationAction("get_task", "Eine einzelne Aufgabe abrufen",
                        Map.of("type", "object",
                                "properties", Map.of("task_id", str("ID der Aufgabe")),
                                "required", List.of("task_id"))),
                new IntegrationAction("create_task", "Eine neue Aufgabe anlegen",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "content", str("Titel der Aufgabe"),
                                        "description", str("Beschreibung (Markdown)"),
                                        "due_string", str("Fälligkeit in natürlicher Sprache, z.B. 'morgen 9 Uhr'"),
                                        "priority", PRIORITY,
                                        "labels", LABELS,
                                        "project_id", str("Projekt-ID (leer = Eingang)")),
                                "required", List.of("content"))),
                new IntegrationAction("update_task", "Eine Aufgabe ändern (nur übergebene Felder)",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "task_id", str("ID der Aufgabe"),
                                        "content", str("Neuer Titel"),
                                        "description", str("Neue Beschreibung"),
                                        "due_string", str("Neue Fälligkeit in natürlicher Sprache"),
                                        "priority", PRIORITY,
                                        "labels", LABELS),
                                "required", List.of("task_id"))),
                new IntegrationAction("close_task", "Eine Aufgabe abhaken (wiederkehrende springen zum nächsten Termin)",
                        Map.of("type", "object",
                                "properties", Map.of("task_id", str("ID der Aufgabe")),
                                "required", List.of("task_id"))),
                new IntegrationAction("add_comment", "Einen Kommentar zu einer Aufgabe hinzufügen",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "task_id", str("ID der Aufgabe"),
                                        "content", str("Kommentartext (Markdown, max. 15000 Zeichen)")),
                                "required", List.of("task_id", "content")))
        );
    }
}
