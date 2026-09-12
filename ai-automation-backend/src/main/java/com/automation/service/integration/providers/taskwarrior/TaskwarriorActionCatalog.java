package com.automation.service.integration.providers.taskwarrior;

import com.automation.service.integration.IntegrationAction;

import java.util.List;
import java.util.Map;

/**
 * Static catalog of the Taskwarrior actions (name, description, input schema). Kept apart from
 * {@link TaskwarriorProvider}, which owns CLI execution and action dispatch.
 */
final class TaskwarriorActionCatalog {

    private TaskwarriorActionCatalog() {}

    static List<IntegrationAction> actions() {
        return List.of(
                new IntegrationAction("list_tasks", "Aufgaben auflisten (optional gefiltert)",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "filter", Map.of("type", "string",
                                                "description", "Taskwarrior-Filter, z.B. 'status:pending', 'project:Haus +OVERDUE' (Standard: status:pending)")
                                )
                        )),
                new IntegrationAction("get_task", "Eine einzelne Aufgabe abrufen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "id", Map.of("type", "string", "description", "Aufgaben-UUID oder numerische ID")
                                ),
                                "required", List.of("id")
                        )),
                new IntegrationAction("add_task", "Eine neue Aufgabe anlegen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "description", Map.of("type", "string", "description", "Aufgabentext"),
                                        "project", Map.of("type", "string", "description", "Projektname"),
                                        "priority", Map.of("type", "string", "description", "Priorität: H, M oder L"),
                                        "due", Map.of("type", "string", "description", "Fälligkeit, z.B. 'tomorrow', '2026-07-01'"),
                                        "tags", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Tags ohne '+'")
                                ),
                                "required", List.of("description")
                        )),
                new IntegrationAction("complete_task", "Eine Aufgabe als erledigt markieren",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "id", Map.of("type", "string", "description", "Aufgaben-UUID oder numerische ID")
                                ),
                                "required", List.of("id")
                        )),
                new IntegrationAction("modify_task", "Eine Aufgabe ändern",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "id", Map.of("type", "string", "description", "Aufgaben-UUID oder numerische ID"),
                                        "description", Map.of("type", "string", "description", "Neuer Aufgabentext"),
                                        "project", Map.of("type", "string", "description", "Projektname"),
                                        "priority", Map.of("type", "string", "description", "Priorität: H, M oder L"),
                                        "due", Map.of("type", "string", "description", "Fälligkeit"),
                                        "tags", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Tags ohne '+'")
                                ),
                                "required", List.of("id")
                        )),
                new IntegrationAction("annotate_task", "Eine Notiz zu einer Aufgabe hinzufügen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "id", Map.of("type", "string", "description", "Aufgaben-UUID oder numerische ID"),
                                        "text", Map.of("type", "string", "description", "Notiztext")
                                ),
                                "required", List.of("id", "text")
                        )),
                new IntegrationAction("delete_task", "Eine Aufgabe löschen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "id", Map.of("type", "string", "description", "Aufgaben-UUID oder numerische ID")
                                ),
                                "required", List.of("id")
                        )),
                new IntegrationAction("list_projects", "Alle Projekte auflisten",
                        Map.of("type", "object", "properties", Map.of())),
                new IntegrationAction("sync_task", "Mit dem konfigurierten Taskwarrior-Sync-Server synchronisieren",
                        Map.of("type", "object", "properties", Map.of()))
        );
    }
}
