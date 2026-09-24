package com.automation.service.integration.providers.gitea;

import com.automation.service.integration.IntegrationAction;

import java.util.List;
import java.util.Map;

/**
 * Static catalog of the Gitea/Forgejo actions (name, description, input schema).
 * Kept apart from {@link GiteaProvider}, which owns HTTP and execution. Gitea tokens carry scopes,
 * but the API offers no way to read them back with the token alone — so no action declares any.
 */
final class GiteaActionCatalog {

    private static final Map<String, Object> REPO = Map.of("type", "string", "description", "Repository als 'owner/repo'");
    private static final Map<String, Object> NUMBER = Map.of("type", "integer", "description", "Issue-Nummer");
    private static final Map<String, Object> PER_PAGE = Map.of("type", "integer", "default", 30,
            "description", "Max. Ergebnisse pro Seite (höchstens 50)");
    private static final Map<String, Object> PAGE = Map.of("type", "integer", "default", 1, "description", "Seitennummer");
    private static final Map<String, Object> LABELS = Map.of("type", "string",
            "description", "Labels (Namen oder IDs, kommagetrennt; Namen müssen im Repository existieren)");

    private GiteaActionCatalog() {}

    static List<IntegrationAction> actions() {
        return List.of(
                new IntegrationAction("list_repos", "Zugängliche Repositories auflisten",
                        Map.of("type", "object", "properties", Map.of("perPage", PER_PAGE, "page", PAGE))),
                new IntegrationAction("list_issues", "Issues eines Repositories auflisten",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "repo", REPO,
                                        "state", Map.of("type", "string", "default", "open", "description", "Status: open, closed, all"),
                                        "labels", Map.of("type", "string", "description", "Nur Issues mit diesen Labels (Namen, kommagetrennt)"),
                                        "since", Map.of("type", "string", "description", "Nur seit Zeitpunkt geänderte (ISO 8601)"),
                                        "perPage", PER_PAGE,
                                        "page", PAGE
                                ),
                                "required", List.of("repo")
                        )),
                new IntegrationAction("get_issue", "Ein einzelnes Issue abrufen",
                        Map.of("type", "object", "properties", Map.of("repo", REPO, "number", NUMBER),
                                "required", List.of("repo", "number"))),
                new IntegrationAction("create_issue", "Ein neues Issue erstellen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "repo", REPO,
                                        "title", Map.of("type", "string", "description", "Titel des Issues"),
                                        "body", Map.of("type", "string", "description", "Beschreibung (Markdown)"),
                                        "labels", LABELS,
                                        "assignees", Map.of("type", "string", "description", "Bearbeiter (Benutzernamen, kommagetrennt)")
                                ),
                                "required", List.of("repo", "title")
                        )),
                new IntegrationAction("update_issue", "Ein Issue aktualisieren (inkl. öffnen/schliessen)",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "repo", REPO,
                                        "number", NUMBER,
                                        "title", Map.of("type", "string", "description", "Neuer Titel"),
                                        "body", Map.of("type", "string", "description", "Neue Beschreibung (Markdown)"),
                                        "state", Map.of("type", "string", "description", "Status: open, closed"),
                                        "labels", LABELS,
                                        "assignees", Map.of("type", "string", "description", "Bearbeiter (kommagetrennt, ersetzt bestehende)")
                                ),
                                "required", List.of("repo", "number")
                        )),
                new IntegrationAction("add_comment", "Einen Kommentar zu einem Issue hinzufügen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "repo", REPO,
                                        "number", NUMBER,
                                        "body", Map.of("type", "string", "description", "Kommentartext (Markdown)")
                                ),
                                "required", List.of("repo", "number", "body")
                        ))
        );
    }
}
