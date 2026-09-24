package com.automation.service.integration.providers.slack;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.automation.service.integration.providers.slack.SlackParams.*;

/**
 * Slack integration provider — posts messages and reads channels via the Slack Web API.
 *
 * <p>Authentication uses the bot token ({@code xoxb-…}) of a Slack app installed in the workspace,
 * not the OAuth2 flow ({@link #requiresOAuthFlow()} is {@code false}). The framework stores the
 * credential as {@code "<label>|<token>"}; the label is optional and informational. The granted
 * scopes come from the {@code x-oauth-scopes} header of {@code auth.test}.
 *
 * <p>Only simulated so far (no entry in {@code VerifiedProviders}): the tests run against a local
 * stub modelled on the official Web API reference.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlackProvider implements IntegrationProvider {

    private static final TypeReference<List<Object>> LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final SlackHttpClient http;

    @Override
    public String getProviderId() {
        return "slack";
    }

    @Override
    public String getDisplayName() {
        return "Slack";
    }

    @Override
    public String getIcon() {
        return "Hash";
    }

    @Override
    public String getDescription() {
        // Fallback (German); the UI prefers i18n key integrations.providers.slack.description.
        return "Nachrichten in Slack-Kanäle senden, Kanäle auflisten und Verläufe lesen";
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        // Slack offers OAuth v2, but this provider authenticates with the bot token of an
        // installed app (requiresOAuthFlow=false). Kept for completeness and the env-var fallback.
        return new OAuthConfig(
                "https://slack.com/oauth/v2/authorize",
                "https://slack.com/api/oauth.v2.access",
                List.of("chat:write", "channels:read", "channels:history"),
                "SLACK_CLIENT_ID",
                "SLACK_CLIENT_SECRET"
        );
    }

    @Override
    public boolean requiresOAuthFlow() {
        return false;
    }

    /** {@code auth.test}: validates the token and names bot user and workspace. */
    @Override
    public String fetchAccountInfo(String combinedToken) {
        try {
            Map<String, Object> body = http.get("auth.test", Map.of(), extractToken(combinedToken)).body();
            Map<String, Object> info = new LinkedHashMap<>();
            Object user = body.get("user");
            Object team = body.get("team");
            info.put("name", user != null ? user.toString() : "");
            info.put("team", team != null ? team.toString() : "");
            if (body.get("url") != null) info.put("url", body.get("url").toString());
            return objectMapper.writeValueAsString(info);
        } catch (Exception e) {
            log.warn("Failed to fetch Slack account info: {}", e.getMessage());
        }
        return null;
    }

    /** Scopes from the {@code x-oauth-scopes} header, space-separated; {@code null} = unknown. */
    @Override
    public String fetchGrantedScopes(String combinedToken) {
        try {
            return parseScopeHeader(http.get("auth.test", Map.of(), extractToken(combinedToken)).scopesHeader());
        } catch (Exception e) {
            log.warn("Failed to read Slack token scopes: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
        return SlackActionCatalog.actions();
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        String token = extractToken(accessToken);
        return switch (action) {
            case "post_message" -> http.post("chat.postMessage", messageBody(params), token).body();
            case "list_channels" -> http.get("conversations.list", listQuery(params), token).body();
            case "get_channel_history" -> http.get("conversations.history", historyQuery(params), token).body();
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private Map<String, Object> messageBody(Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", requireParam(params, "channel"));
        String text = optionalString(params, "text");
        List<Object> blocks = blocks(params.get("blocks"));
        if (text == null && blocks == null) {
            throw new IllegalArgumentException("Parameter 'text' or 'blocks' is required");
        }
        if (text != null) body.put("text", text);
        if (blocks != null) body.put("blocks", blocks);
        String threadTs = timestamp(params, "thread_ts");
        if (threadTs != null) body.put("thread_ts", threadTs);
        return body;
    }

    /** Block Kit blocks as a list — accepts a JSON array or its JSON text (templated params are strings). */
    private List<Object> blocks(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        if (value instanceof List<?> list) return List.copyOf(list);
        try {
            List<Object> parsed = objectMapper.readValue(value.toString(), LIST);
            return parsed.isEmpty() ? null : parsed;
        } catch (Exception e) {
            throw new IllegalArgumentException("Parameter 'blocks' must be a JSON array of Block Kit blocks");
        }
    }

    private static Map<String, String> listQuery(Map<String, Object> params) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("types", channelTypes(params));
        query.put("exclude_archived", "true");
        query.put("limit", String.valueOf(boundedInt(params, "limit", 1, 1000, 100)));
        String cursor = optionalString(params, "cursor");
        if (cursor != null) query.put("cursor", cursor);
        return query;
    }

    private static Map<String, String> historyQuery(Map<String, Object> params) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("channel", requireParam(params, "channel"));
        query.put("limit", String.valueOf(boundedInt(params, "limit", 1, 999, 100)));
        String cursor = optionalString(params, "cursor");
        if (cursor != null) query.put("cursor", cursor);
        String oldest = timestamp(params, "oldest");
        if (oldest != null) query.put("oldest", oldest);
        String latest = timestamp(params, "latest");
        if (latest != null) query.put("latest", latest);
        return query;
    }
}
