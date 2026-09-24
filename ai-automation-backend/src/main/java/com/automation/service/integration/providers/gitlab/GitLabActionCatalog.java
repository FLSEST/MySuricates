package com.automation.service.integration.providers.gitlab;

import com.automation.service.integration.IntegrationAction;

import java.util.List;
import java.util.Map;

/**
 * Static catalog of the GitLab actions (name, description, input schema, required token scopes).
 * Reading works with {@code read_api} or {@code api}, so read actions declare no scope; writing needs
 * {@code api}. Kept apart from {@link GitLabProvider}, which owns HTTP and execution.
 */
final class GitLabActionCatalog {

    static final List<String> WRITE_SCOPES = List.of("api");

    private static final Map<String, Object> PROJECT = Map.of("type", "string",
            "description", "Projekt als Pfad 'gruppe/projekt' (auch Untergruppen) oder numerische ID");
    private static final Map<String, Object> IID = Map.of("type", "integer", "description", "Issue-Nummer im Projekt (IID)");
    private static final Map<String, Object> PER_PAGE = Map.of("type", "integer", "default", 30,
            "description", "Max. Ergebnisse pro Seite (höchstens 100)");
    private static final Map<String, Object> PAGE = Map.of("type", "integer", "default", 1, "description", "Seitennummer");
    private static final Map<String, Object> LABELS = Map.of("type", "string", "description", "Labels (Namen, kommagetrennt)");
    private static final Map<String, Object> ASSIGNEES = Map.of("type", "string",
            "description", "Bearbeiter als numerische Benutzer-IDs (kommagetrennt)");

    private GitLabActionCatalog() {}

    static List<IntegrationAction> actions() {
        return List.of(
                new IntegrationAction("list_projects", "Projekte auflisten, in denen der Token-Inhaber Mitglied ist",
                        Map.of("type", "object", "properties", Map.of(
                                "search", Map.of("type", "string", "description", "Nur Projekte, deren Name dies enthält"),
                                "perPage", PER_PAGE,
                                "page", PAGE))),
                new IntegrationAction("list_issues", "Issues eines Projekts auflisten",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "project", PROJECT,
                                        "state", Map.of("type", "string", "default", "open", "description", "Status: open, closed, all"),
                                        "labels", Map.of("type", "string", "description", "Nur Issues mit allen diesen Labels (kommagetrennt)"),
                                        "since", Map.of("type", "string", "description", "Nur seit Zeitpunkt geänderte (ISO 8601)"),
                                        "perPage", PER_PAGE,
                                        "page", PAGE
                                ),
                                "required", List.of("project")
                        )),
                new IntegrationAction("get_issue", "Ein einzelnes Issue abrufen",
                        Map.of("type", "object", "properties", Map.of("project", PROJECT, "number", IID),
                                "required", List.of("project", "number"))),
                new IntegrationAction("create_issue", "Ein neues Issue erstellen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "project", PROJECT,
                                        "title", Map.of("type", "string", "description", "Titel des Issues"),
                                        "body", Map.of("type", "string", "description", "Beschreibung (Markdown)"),
                                        "labels", LABELS,
                                        "assigneeIds", ASSIGNEES
                                ),
                                "required", List.of("project", "title")
                        ), WRITE_SCOPES),
                new IntegrationAction("update_issue", "Ein Issue aktualisieren (inkl. schliessen/wieder öffnen)",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "project", PROJECT,
                                        "number", IID,
                                        "title", Map.of("type", "string", "description", "Neuer Titel"),
                                        "body", Map.of("type", "string", "description", "Neue Beschreibung (Markdown)"),
                                        "state", Map.of("type", "string", "description", "Status: open (wieder öffnen), closed (schliessen)"),
                                        "labels", Map.of("type", "string", "description", "Labels (kommagetrennt, ersetzt bestehende)"),
                                        "assigneeIds", ASSIGNEES
                                ),
                                "required", List.of("project", "number")
                        ), WRITE_SCOPES),
                new IntegrationAction("add_comment", "Einen Kommentar (Note) zu einem Issue hinzufügen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "project", PROJECT,
                                        "number", IID,
                                        "body", Map.of("type", "string", "description", "Kommentartext (Markdown)")
                                ),
                                "required", List.of("project", "number", "body")
                        ), WRITE_SCOPES)
        );
    }
}
