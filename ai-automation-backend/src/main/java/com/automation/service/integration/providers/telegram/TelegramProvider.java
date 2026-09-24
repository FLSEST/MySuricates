package com.automation.service.integration.providers.telegram;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.automation.service.integration.providers.telegram.TelegramParams.*;

/**
 * Telegram integration provider — sends messages and polls updates through the Bot API.
 *
 * <p>Authentication is the bot token from @BotFather; there is no OAuth at all
 * ({@link #requiresOAuthFlow()} is {@code false}). The framework stores the credential as
 * {@code "<label>|<token>"}; the label is optional and informational. The token sits in the request
 * path, so {@link TelegramHttpClient} keeps it out of every message.
 *
 * <p>Only simulated so far (no entry in {@code VerifiedProviders}): the tests run against a local
 * stub modelled on the official Bot API reference.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TelegramProvider implements IntegrationProvider {

    private final ObjectMapper objectMapper;
    private final TelegramHttpClient http;

    @Override
    public String getProviderId() {
        return "telegram";
    }

    @Override
    public String getDisplayName() {
        return "Telegram";
    }

    @Override
    public String getIcon() {
        return "Send";
    }

    @Override
    public String getDescription() {
        // Fallback (German); the UI prefers i18n key integrations.providers.telegram.description.
        return "Nachrichten über einen Telegram-Bot senden und eingehende Updates abrufen";
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        // The Bot API has no OAuth: the bot token is the whole credential. Empty endpoints, and the
        // env vars only serve the "configured" fallback check.
        return new OAuthConfig("", "", List.of(), "TELEGRAM_BOT_NAME", "TELEGRAM_BOT_TOKEN");
    }

    @Override
    public boolean requiresOAuthFlow() {
        return false;
    }

    /** {@code getMe}: validates the token and names the bot. */
    @Override
    public String fetchAccountInfo(String combinedToken) {
        try {
            Object me = http.call(extractToken(combinedToken), "getMe", Map.of());
            if (me instanceof Map<?, ?> bot) {
                Map<String, Object> info = new LinkedHashMap<>();
                Object firstName = bot.get("first_name");
                Object username = bot.get("username");
                info.put("name", firstName != null ? firstName.toString() : "");
                if (username != null) info.put("username", "@" + username);
                return objectMapper.writeValueAsString(info);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch Telegram bot info: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
        return List.of(
                new IntegrationAction("send_message", "Nachricht an einen Chat, eine Gruppe oder einen Kanal senden (sendMessage)",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "chat_id", Map.of("type", "string",
                                                "description", "Chat-ID (Zahl, Gruppen negativ) oder @kanalname"),
                                        "text", Map.of("type", "string", "description", "Nachrichtentext (1–4096 Zeichen)"),
                                        "parse_mode", Map.of("type", "string",
                                                "description", "Optional: MarkdownV2 oder HTML (Sonderzeichen müssen dann escaped sein)"),
                                        "disable_notification", Map.of("type", "boolean", "default", false,
                                                "description", "Stumm zustellen (ohne Benachrichtigungston)")),
                                "required", List.of("chat_id", "text"))),
                new IntegrationAction("get_updates",
                        "Eingehende Updates abrufen (getUpdates) — funktioniert nicht, solange ein Webhook gesetzt ist (Telegram "
                                + "antwortet dann mit 409 Conflict); ein 'offset' über der letzten update_id bestätigt die älteren",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "offset", Map.of("type", "integer",
                                                "description", "Erste gewünschte update_id (letzte update_id + 1 bestätigt die bisherigen)"),
                                        "limit", Map.of("type", "integer", "default", 100, "description", "Anzahl Updates (1–100)"))))
        );
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        String token = extractToken(accessToken);
        return switch (action) {
            case "send_message" -> http.call(token, "sendMessage", messageBody(params));
            case "get_updates" -> http.call(token, "getUpdates", updatesBody(params));
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private static Map<String, Object> messageBody(Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId(params));
        body.put("text", text(params));
        String parseMode = parseMode(params);
        if (parseMode != null) body.put("parse_mode", parseMode);
        Boolean silent = bool(params, "disable_notification");
        if (silent != null) body.put("disable_notification", silent);
        return body;
    }

    private static Map<String, Object> updatesBody(Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        Long offset = optionalLong(params, "offset");
        if (offset != null) body.put("offset", offset);
        body.put("limit", updatesLimit(params));
        body.put("timeout", 0); // short poll: a workflow step must not hang on long polling
        return body;
    }
}
