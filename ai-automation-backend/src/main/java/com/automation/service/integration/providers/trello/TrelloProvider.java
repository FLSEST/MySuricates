package com.automation.service.integration.providers.trello;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class TrelloProvider implements IntegrationProvider {

    private static final String BASE_URL = "https://api.trello.com/1";

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String getProviderId() {
        return "trello";
    }

    @Override
    public String getDisplayName() {
        return "Trello";
    }

    @Override
    public String getIcon() {
        return "KanbanSquare";
    }

    @Override
    public String getDescription() {
        return "Trello-Boards, Listen und Karten verwalten";
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        return new OAuthConfig(
                "https://auth.atlassian.com/authorize",
                "https://auth.atlassian.com/oauth/token",
                List.of("read:trello", "write:trello"),
                "TRELLO_CLIENT_ID",
                "TRELLO_CLIENT_SECRET",
                Map.of("audience", "api.atlassian.com", "prompt", "consent")
        );
    }

    @Override
    public String fetchAccountInfo(String accessToken) {
        try {
            TrelloMember member = httpGet(BASE_URL + "/members/me", accessToken, TrelloMember.class);
            return objectMapper.writeValueAsString(Map.of(
                    "name", member.fullName() != null ? member.fullName() : member.username(),
                    "email", member.username()
            ));
        } catch (Exception e) {
            log.warn("Failed to fetch Trello account info: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
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

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        return switch (action) {
            case "list_boards" -> listBoards(params, accessToken);
            case "get_board_lists" -> getBoardLists(params, accessToken);
            case "get_list_cards" -> getListCards(params, accessToken);
            case "get_card" -> getCard(params, accessToken);
            case "create_card" -> createCard(params, accessToken);
            case "update_card" -> updateCard(params, accessToken);
            case "delete_card" -> deleteCard(params, accessToken);
            case "add_comment" -> addComment(params, accessToken);
            case "search" -> search(params, accessToken);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private Object listBoards(Map<String, Object> params, String accessToken) {
        String filter = paramString(params, "filter", "open");
        String url = BASE_URL + "/members/me/boards?filter=" + urlEncode(filter);
        return httpGet(url, accessToken, TrelloBoard[].class);
    }

    private Object getBoardLists(Map<String, Object> params, String accessToken) {
        String boardId = requireParam(params, "boardId");
        String filter = paramString(params, "filter", "open");
        String url = BASE_URL + "/boards/" + urlEncode(boardId) + "/lists?filter=" + urlEncode(filter);
        return httpGet(url, accessToken, TrelloList[].class);
    }

    private Object getListCards(Map<String, Object> params, String accessToken) {
        String listId = requireParam(params, "listId");
        String url = BASE_URL + "/lists/" + urlEncode(listId) + "/cards";
        return httpGet(url, accessToken, TrelloCard[].class);
    }

    private Object getCard(Map<String, Object> params, String accessToken) {
        String cardId = requireParam(params, "cardId");
        String url = BASE_URL + "/cards/" + urlEncode(cardId);
        return httpGet(url, accessToken, TrelloCard.class);
    }

    private Object createCard(Map<String, Object> params, String accessToken) {
        requireParam(params, "idList");
        requireParam(params, "name");

        StringBuilder url = new StringBuilder(BASE_URL + "/cards?");
        url.append("idList=").append(urlEncode(paramString(params, "idList", "")));
        url.append("&name=").append(urlEncode(paramString(params, "name", "")));
        if (params.containsKey("desc")) url.append("&desc=").append(urlEncode(paramString(params, "desc", "")));
        if (params.containsKey("due")) url.append("&due=").append(urlEncode(paramString(params, "due", "")));
        if (params.containsKey("idMembers")) url.append("&idMembers=").append(urlEncode(paramString(params, "idMembers", "")));
        if (params.containsKey("idLabels")) url.append("&idLabels=").append(urlEncode(paramString(params, "idLabels", "")));
        url.append("&pos=").append(urlEncode(paramString(params, "pos", "bottom")));

        return httpPost(url.toString(), null, accessToken, TrelloCard.class);
    }

    private Object updateCard(Map<String, Object> params, String accessToken) {
        String cardId = requireParam(params, "cardId");

        StringBuilder url = new StringBuilder(BASE_URL + "/cards/" + urlEncode(cardId) + "?");
        boolean hasParam = false;
        for (String key : List.of("name", "desc", "due", "idList", "idBoard", "pos")) {
            if (params.containsKey(key)) {
                if (hasParam) url.append("&");
                url.append(urlEncode(key)).append("=").append(urlEncode(paramString(params, key, "")));
                hasParam = true;
            }
        }
        if (params.containsKey("dueComplete")) {
            if (hasParam) url.append("&");
            url.append("dueComplete=").append(params.get("dueComplete"));
            hasParam = true;
        }
        if (params.containsKey("closed")) {
            if (hasParam) url.append("&");
            url.append("closed=").append(params.get("closed"));
        }

        return httpPut(url.toString(), accessToken, TrelloCard.class);
    }

    private Object deleteCard(Map<String, Object> params, String accessToken) {
        String cardId = requireParam(params, "cardId");
        String url = BASE_URL + "/cards/" + urlEncode(cardId);
        httpDelete(url, accessToken);
        return Map.of("deleted", true, "cardId", cardId);
    }

    private Object addComment(Map<String, Object> params, String accessToken) {
        String cardId = requireParam(params, "cardId");
        String text = requireParam(params, "text");
        String url = BASE_URL + "/cards/" + urlEncode(cardId) + "/actions/comments?text=" + urlEncode(text);
        return httpPost(url, null, accessToken, TrelloComment.class);
    }

    private Object search(Map<String, Object> params, String accessToken) {
        String query = requireParam(params, "query");
        String modelTypes = paramString(params, "modelTypes", "cards,boards");
        int cardsLimit = paramInt(params, "cards_limit", 10);
        int boardsLimit = paramInt(params, "boards_limit", 5);

        String url = BASE_URL + "/search"
                + "?query=" + urlEncode(query)
                + "&modelTypes=" + urlEncode(modelTypes)
                + "&cards_limit=" + cardsLimit
                + "&boards_limit=" + boardsLimit;

        return httpGet(url, accessToken, TrelloSearchResult.class);
    }

    // --- HTTP helpers ---

    private <T> T httpGet(String url, String accessToken, Class<T> responseType) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return objectMapper.readValue(response.body(), responseType);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Trello API request failed: " + e.getMessage(), e);
        }
    }

    private <T> T httpPost(String url, Object body, String accessToken, Class<T> responseType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .timeout(Duration.ofSeconds(15));

            if (body != null) {
                String json = objectMapper.writeValueAsString(body);
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json));
            } else {
                builder.POST(HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return objectMapper.readValue(response.body(), responseType);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Trello API request failed: " + e.getMessage(), e);
        }
    }

    private <T> T httpPut(String url, String accessToken, Class<T> responseType) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return objectMapper.readValue(response.body(), responseType);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Trello API request failed: " + e.getMessage(), e);
        }
    }

    private void httpDelete(String url, String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .DELETE()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 204 && response.statusCode() != 200) {
                checkResponse(response);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Trello API request failed: " + e.getMessage(), e);
        }
    }

    private void checkResponse(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Trello API error " + response.statusCode() + ": " + response.body());
        }
    }

    // --- Parameter helpers ---

    private static String paramString(Map<String, Object> params, String key, String defaultValue) {
        Object val = params.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    private static int paramInt(Map<String, Object> params, String key, int defaultValue) {
        Object val = params.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) return Integer.parseInt(s);
        return defaultValue;
    }

    private static String requireParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null) throw new IllegalArgumentException("Required parameter missing: " + key);
        return val.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
