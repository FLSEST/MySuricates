package com.automation.service.integration.providers.trello;

import com.automation.service.integration.IntegrationAction;

import java.util.List;
import java.util.Map;

/**
 * Static catalog of the Trello actions (name, description, input schema).
 * Kept apart from {@link TrelloProvider}, which owns HTTP and execution.
 */
final class TrelloActionCatalog {

    private TrelloActionCatalog() {}

    static List<IntegrationAction> actions() {
        return List.of(
                new IntegrationAction("list_boards", "Alle Boards des Benutzers abrufen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "filter", Map.of("type", "string", "default", "open", "description", "Filter: open, closed, all")
                                )
                        )),
                new IntegrationAction("get_board_lists", "Listen eines Boards abrufen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "boardId", Map.of("type", "string", "description", "Board-ID"),
                                        "filter", Map.of("type", "string", "default", "open", "description", "Filter: open, closed, all")
                                ),
                                "required", List.of("boardId")
                        )),
                new IntegrationAction("get_list_cards", "Karten einer Liste abrufen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "listId", Map.of("type", "string", "description", "Listen-ID")
                                ),
                                "required", List.of("listId")
                        )),
                new IntegrationAction("get_card", "Eine einzelne Karte abrufen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "cardId", Map.of("type", "string", "description", "Karten-ID")
                                ),
                                "required", List.of("cardId")
                        )),
                new IntegrationAction("create_card", "Eine neue Karte erstellen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "idList", Map.of("type", "string", "description", "Ziel-Listen-ID"),
                                        "name", Map.of("type", "string", "description", "Titel der Karte"),
                                        "desc", Map.of("type", "string", "description", "Beschreibung"),
                                        "due", Map.of("type", "string", "description", "Fälligkeitsdatum (ISO 8601)"),
                                        "idMembers", Map.of("type", "string", "description", "Mitglieder-IDs (kommagetrennt)"),
                                        "idLabels", Map.of("type", "string", "description", "Label-IDs (kommagetrennt)"),
                                        "pos", Map.of("type", "string", "default", "bottom", "description", "Position: top, bottom oder Zahl")
                                ),
                                "required", List.of("idList", "name")
                        )),
                new IntegrationAction("update_card", "Eine Karte aktualisieren oder verschieben",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "cardId", Map.of("type", "string", "description", "Karten-ID"),
                                        "name", Map.of("type", "string", "description", "Neuer Titel"),
                                        "desc", Map.of("type", "string", "description", "Neue Beschreibung"),
                                        "due", Map.of("type", "string", "description", "Fälligkeitsdatum (ISO 8601)"),
                                        "dueComplete", Map.of("type", "boolean", "description", "Fälligkeit erledigt"),
                                        "idList", Map.of("type", "string", "description", "Ziel-Listen-ID (verschiebt die Karte)"),
                                        "idBoard", Map.of("type", "string", "description", "Ziel-Board-ID (verschiebt die Karte)"),
                                        "closed", Map.of("type", "boolean", "description", "Karte archivieren"),
                                        "pos", Map.of("type", "string", "description", "Position: top, bottom oder Zahl")
                                ),
                                "required", List.of("cardId")
                        )),
                new IntegrationAction("delete_card", "Eine Karte dauerhaft löschen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "cardId", Map.of("type", "string", "description", "Karten-ID")
                                ),
                                "required", List.of("cardId")
                        )),
                new IntegrationAction("add_comment", "Einen Kommentar zu einer Karte hinzufügen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "cardId", Map.of("type", "string", "description", "Karten-ID"),
                                        "text", Map.of("type", "string", "description", "Kommentartext")
                                ),
                                "required", List.of("cardId", "text")
                        )),
                new IntegrationAction("search", "Boards und Karten durchsuchen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of("type", "string", "description", "Suchbegriff"),
                                        "modelTypes", Map.of("type", "string", "default", "cards,boards", "description", "Typen: cards, boards (kommagetrennt)"),
                                        "cards_limit", Map.of("type", "integer", "default", 10, "description", "Max. Karten"),
                                        "boards_limit", Map.of("type", "integer", "default", 5, "description", "Max. Boards")
                                ),
                                "required", List.of("query")
                        ))
        );
    }
}
