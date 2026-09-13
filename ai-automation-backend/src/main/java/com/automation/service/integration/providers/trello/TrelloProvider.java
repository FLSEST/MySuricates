package com.automation.service.integration.providers.trello;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.automation.service.integration.TaskExportCapable;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.automation.service.integration.providers.trello.TrelloParams.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class TrelloProvider implements IntegrationProvider, TaskExportCapable {

    private static final String BASE_URL = "https://api.trello.com/1";

    private final ObjectMapper objectMapper;
    private final TrelloHttpClient http;

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
    public boolean requiresOAuthFlow() {
        return false;
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        return new OAuthConfig(
                "https://trello.com/1/authorize",
                "https://trello.com/1/authorize",
                List.of("read", "write"),
                "TRELLO_API_KEY",
                "TRELLO_TOKEN"
        );
    }

    @Override
    public String fetchAccountInfo(String combinedToken) {
        try {
            String authParams = buildAuthParams(combinedToken);
            String url = BASE_URL + "/members/me?" + authParams;

            HttpResponse<String> response = http.getRaw(url);
            if (response.statusCode() == 401) {
                throw new IllegalStateException("Trello authentication failed — check API key and token");
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Trello API error " + response.statusCode() + ": " + response.body());
            }

            TrelloMember member = objectMapper.readValue(response.body(), TrelloMember.class);
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
        return TrelloActionCatalog.actions();
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
        String url = BASE_URL + "/members/me/boards?" + buildAuthParams(accessToken) + "&filter=" + urlEncode(filter);
        return http.get(url, TrelloBoard[].class);
    }

    private Object getBoardLists(Map<String, Object> params, String accessToken) {
        String boardId = requireParam(params, "boardId");
        String filter = paramString(params, "filter", "open");
        String url = BASE_URL + "/boards/" + urlEncode(boardId) + "/lists?" + buildAuthParams(accessToken) + "&filter=" + urlEncode(filter);
        return http.get(url, TrelloList[].class);
    }

    private Object getListCards(Map<String, Object> params, String accessToken) {
        String listId = requireParam(params, "listId");
        String url = BASE_URL + "/lists/" + urlEncode(listId) + "/cards?" + buildAuthParams(accessToken);
        return http.get(url, TrelloCard[].class);
    }

    private Object getCard(Map<String, Object> params, String accessToken) {
        String cardId = requireParam(params, "cardId");
        String url = BASE_URL + "/cards/" + urlEncode(cardId) + "?" + buildAuthParams(accessToken);
        return http.get(url, TrelloCard.class);
    }

    private Object createCard(Map<String, Object> params, String accessToken) {
        requireParam(params, "idList");
        requireParam(params, "name");

        StringBuilder url = new StringBuilder(BASE_URL + "/cards?" + buildAuthParams(accessToken));
        url.append("&idList=").append(urlEncode(paramString(params, "idList", "")));
        url.append("&name=").append(urlEncode(paramString(params, "name", "")));
        if (params.containsKey("desc")) url.append("&desc=").append(urlEncode(paramString(params, "desc", "")));
        if (params.containsKey("due")) url.append("&due=").append(urlEncode(paramString(params, "due", "")));
        if (params.containsKey("idMembers")) url.append("&idMembers=").append(urlEncode(paramString(params, "idMembers", "")));
        if (params.containsKey("idLabels")) url.append("&idLabels=").append(urlEncode(paramString(params, "idLabels", "")));
        url.append("&pos=").append(urlEncode(paramString(params, "pos", "bottom")));

        return http.post(url.toString(), TrelloCard.class);
    }

    private Object updateCard(Map<String, Object> params, String accessToken) {
        String cardId = requireParam(params, "cardId");

        StringBuilder url = new StringBuilder(BASE_URL + "/cards/" + urlEncode(cardId) + "?" + buildAuthParams(accessToken));
        for (String key : List.of("name", "desc", "due", "idList", "idBoard", "pos")) {
            if (params.containsKey(key)) {
                url.append("&").append(urlEncode(key)).append("=").append(urlEncode(paramString(params, key, "")));
            }
        }
        if (params.containsKey("dueComplete")) url.append("&dueComplete=").append(params.get("dueComplete"));
        if (params.containsKey("closed")) url.append("&closed=").append(params.get("closed"));

        return http.put(url.toString(), TrelloCard.class);
    }

    private Object deleteCard(Map<String, Object> params, String accessToken) {
        String cardId = requireParam(params, "cardId");
        String url = BASE_URL + "/cards/" + urlEncode(cardId) + "?" + buildAuthParams(accessToken);
        http.delete(url);
        return Map.of("deleted", true, "cardId", cardId);
    }

    private Object addComment(Map<String, Object> params, String accessToken) {
        String cardId = requireParam(params, "cardId");
        String text = requireParam(params, "text");
        String url = BASE_URL + "/cards/" + urlEncode(cardId) + "/actions/comments?" + buildAuthParams(accessToken) + "&text=" + urlEncode(text);
        return http.post(url, TrelloComment.class);
    }

    private Object search(Map<String, Object> params, String accessToken) {
        String query = requireParam(params, "query");
        String modelTypes = paramString(params, "modelTypes", "cards,boards");
        int cardsLimit = paramInt(params, "cards_limit", 10);
        int boardsLimit = paramInt(params, "boards_limit", 5);

        String url = BASE_URL + "/search?" + buildAuthParams(accessToken)
                + "&query=" + urlEncode(query)
                + "&modelTypes=" + urlEncode(modelTypes)
                + "&cards_limit=" + cardsLimit
                + "&boards_limit=" + boardsLimit;

        return http.get(url, TrelloSearchResult.class);
    }

    // --- TaskExportCapable (integrations-agnostischer Single-TODO-Export, target = Listen-ID) ---

    @Override
    public TaskRef createTask(TaskExportInput input, String accessToken) {
        if (input.target() == null || input.target().isBlank()) {
            throw new IllegalArgumentException("Trello export needs a list id as target");   // before any request
        }
        String name = input.title() != null && !input.title().isBlank() ? input.title() : "TODO";
        StringBuilder url = new StringBuilder(BASE_URL + "/cards?" + buildAuthParams(accessToken));
        url.append("&idList=").append(urlEncode(input.target().trim()));
        url.append("&name=").append(urlEncode(name));
        if (input.body() != null && !input.body().isBlank()) url.append("&desc=").append(urlEncode(input.body()));
        url.append("&pos=bottom");
        // labels are not mapped: Trello expects label ids, MySuricates tags are free text

        TrelloCard card = http.post(url.toString(), TrelloCard.class);
        if (card == null || card.id() == null) {
            throw new IllegalStateException("Trello card creation returned no id");
        }
        return new TaskRef("trello", card.id(), card.shortUrl() != null ? card.shortUrl() : card.url());
    }

    /** Every open list of every open board, labelled "Board / Liste". */
    @Override
    public List<TaskTarget> listTargets(String accessToken) {
        String auth = buildAuthParams(accessToken);
        TrelloBoard[] boards = http.get(BASE_URL + "/members/me/boards?" + auth + "&filter=open", TrelloBoard[].class);
        List<TaskTarget> out = new ArrayList<>();
        if (boards == null) return out;
        for (TrelloBoard board : boards) {
            TrelloList[] lists = http.get(BASE_URL + "/boards/" + urlEncode(board.id()) + "/lists?" + auth + "&filter=open",
                    TrelloList[].class);
            if (lists == null) continue;
            for (TrelloList list : lists) {
                out.add(new TaskTarget(list.id(), board.name() + " / " + list.name()));
            }
        }
        return out;
    }

    // --- Auth helper ---

    private String buildAuthParams(String combinedToken) {
        String[] parts = combinedToken.split("\\|", 2);
        if (parts.length != 2) {
            throw new IllegalStateException("Invalid Trello credentials. Expected 'apiKey|token' format.");
        }
        return "key=" + urlEncode(parts[0]) + "&token=" + urlEncode(parts[1]);
    }
}
