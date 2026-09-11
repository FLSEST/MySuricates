package com.automation.service.integration.providers.github;

import com.automation.service.integration.IntegrationAction;

import java.util.List;
import java.util.Map;

/**
 * Static catalog of the GitHub actions (name, description, input schema, required PAT scopes).
 * Kept apart from {@link GitHubProvider}, which owns HTTP and execution.
 */
final class GitHubActionCatalog {

    private GitHubActionCatalog() {}

    static List<IntegrationAction> actions() {
        return List.of(
                new IntegrationAction("list_repos", "Zugängliche Repositories auflisten",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "perPage", Map.of("type", "integer", "default", 30, "description", "Max. Ergebnisse pro Seite"),
                                        "page", Map.of("type", "integer", "default", 1, "description", "Seitennummer")
                                )
                        )),
                new IntegrationAction("list_issues", "Issues eines Repositories auflisten",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "repo", Map.of("type", "string", "description", "Repository als 'owner/repo'"),
                                        "state", Map.of("type", "string", "default", "open", "description", "Status: open, closed, all"),
                                        "labels", Map.of("type", "string", "description", "Labels (kommagetrennt)"),
                                        "since", Map.of("type", "string", "description", "Nur seit Zeitpunkt geänderte (ISO 8601)"),
                                        "perPage", Map.of("type", "integer", "default", 50, "description", "Max. Ergebnisse pro Seite"),
                                        "page", Map.of("type", "integer", "default", 1, "description", "Seitennummer")
                                ),
                                "required", List.of("repo")
                        )),
                new IntegrationAction("get_issue", "Ein einzelnes Issue abrufen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "repo", Map.of("type", "string", "description", "Repository als 'owner/repo'"),
                                        "number", Map.of("type", "integer", "description", "Issue-Nummer")
                                ),
                                "required", List.of("repo", "number")
                        )),
                new IntegrationAction("create_issue", "Ein neues Issue erstellen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "repo", Map.of("type", "string", "description", "Repository als 'owner/repo'"),
                                        "title", Map.of("type", "string", "description", "Titel des Issues"),
                                        "body", Map.of("type", "string", "description", "Beschreibung (Markdown)"),
                                        "labels", Map.of("type", "string", "description", "Labels (kommagetrennt)"),
                                        "assignees", Map.of("type", "string", "description", "Bearbeiter (Benutzernamen, kommagetrennt)")
                                ),
                                "required", List.of("repo", "title")
                        ), List.of("repo")),
                new IntegrationAction("update_issue", "Ein Issue aktualisieren (inkl. öffnen/schliessen)",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "repo", Map.of("type", "string", "description", "Repository als 'owner/repo'"),
                                        "number", Map.of("type", "integer", "description", "Issue-Nummer"),
                                        "title", Map.of("type", "string", "description", "Neuer Titel"),
                                        "body", Map.of("type", "string", "description", "Neue Beschreibung (Markdown)"),
                                        "state", Map.of("type", "string", "description", "Status: open, closed"),
                                        "labels", Map.of("type", "string", "description", "Labels (kommagetrennt, ersetzt bestehende)"),
                                        "assignees", Map.of("type", "string", "description", "Bearbeiter (kommagetrennt, ersetzt bestehende)")
                                ),
                                "required", List.of("repo", "number")
                        ), List.of("repo")),
                new IntegrationAction("add_comment", "Einen Kommentar zu einem Issue hinzufügen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "repo", Map.of("type", "string", "description", "Repository als 'owner/repo'"),
                                        "number", Map.of("type", "integer", "description", "Issue-Nummer"),
                                        "body", Map.of("type", "string", "description", "Kommentartext (Markdown)")
                                ),
                                "required", List.of("repo", "number", "body")
                        ), List.of("repo"))
        );
    }
}
