package com.automation.service.integration.providers.jira;

import com.automation.service.integration.IntegrationAction;

import java.util.List;
import java.util.Map;

/**
 * Static catalog of the Jira actions (name, description, input schema, required OAuth scopes).
 * Kept apart from {@link JiraProvider}, which owns HTTP, cloud-ID resolution and execution.
 */
final class JiraActionCatalog {

    private JiraActionCatalog() {}

    static List<IntegrationAction> actions() {
        return List.of(
                new IntegrationAction("list_projects", "Alle zugänglichen Projekte auflisten",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "maxResults", Map.of("type", "integer", "default", 50, "description", "Max. Ergebnisse"),
                                        "startAt", Map.of("type", "integer", "default", 0, "description", "Offset für Paginierung")
                                )
                        )),
                new IntegrationAction("search_issues", "Issues per JQL durchsuchen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "jql", Map.of("type", "string", "description", "JQL-Query (z.B. 'project = MYPROJ AND status = Open')"),
                                        "maxResults", Map.of("type", "integer", "default", 20, "description", "Max. Ergebnisse"),
                                        "startAt", Map.of("type", "integer", "default", 0, "description", "Offset"),
                                        "fields", Map.of("type", "string", "description", "Felder (kommagetrennt, z.B. 'summary,status,assignee')")
                                ),
                                "required", List.of("jql")
                        )),
                new IntegrationAction("get_issue", "Ein einzelnes Issue abrufen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "issueKey", Map.of("type", "string", "description", "Issue-Key (z.B. PROJ-123)")
                                ),
                                "required", List.of("issueKey")
                        )),
                new IntegrationAction("create_issue", "Ein neues Issue erstellen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "projectKey", Map.of("type", "string", "description", "Projekt-Key (z.B. PROJ)"),
                                        "summary", Map.of("type", "string", "description", "Titel des Issues"),
                                        "issueType", Map.of("type", "string", "default", "Task", "description", "Issue-Typ (Task, Bug, Story, Epic)"),
                                        "description", Map.of("type", "string", "description", "Beschreibung (Klartext)"),
                                        "priority", Map.of("type", "string", "description", "Priorität (Highest, High, Medium, Low, Lowest)"),
                                        "assigneeId", Map.of("type", "string", "description", "Account-ID des Bearbeiters"),
                                        "labels", Map.of("type", "string", "description", "Labels (kommagetrennt)")
                                ),
                                "required", List.of("projectKey", "summary")
                        ), List.of("write:jira-work")),
                new IntegrationAction("update_issue", "Ein Issue aktualisieren",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "issueKey", Map.of("type", "string", "description", "Issue-Key (z.B. PROJ-123)"),
                                        "summary", Map.of("type", "string", "description", "Neuer Titel"),
                                        "description", Map.of("type", "string", "description", "Neue Beschreibung"),
                                        "priority", Map.of("type", "string", "description", "Neue Priorität"),
                                        "assigneeId", Map.of("type", "string", "description", "Neuer Bearbeiter (Account-ID)"),
                                        "labels", Map.of("type", "string", "description", "Labels (kommagetrennt, ersetzt bestehende)")
                                ),
                                "required", List.of("issueKey")
                        ), List.of("write:jira-work")),
                new IntegrationAction("add_comment", "Einen Kommentar zu einem Issue hinzufügen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "issueKey", Map.of("type", "string", "description", "Issue-Key (z.B. PROJ-123)"),
                                        "body", Map.of("type", "string", "description", "Kommentartext")
                                ),
                                "required", List.of("issueKey", "body")
                        ), List.of("write:jira-work")),
                new IntegrationAction("get_transitions", "Verfügbare Status-Übergänge eines Issues abrufen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "issueKey", Map.of("type", "string", "description", "Issue-Key (z.B. PROJ-123)")
                                ),
                                "required", List.of("issueKey")
                        )),
                new IntegrationAction("transition_issue", "Status-Übergang eines Issues ausführen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "issueKey", Map.of("type", "string", "description", "Issue-Key (z.B. PROJ-123)"),
                                        "transitionId", Map.of("type", "string", "description", "Transition-ID (aus get_transitions)")
                                ),
                                "required", List.of("issueKey", "transitionId")
                        ), List.of("write:jira-work"))
        );
    }
}
