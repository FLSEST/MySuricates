package com.automation.service.integration.providers.slack;

import com.automation.service.integration.IntegrationAction;

import java.util.List;
import java.util.Map;

/**
 * Static catalog of the Slack actions (Web API methods) with the bot-token scopes each one needs.
 * Kept apart from {@link SlackProvider}, which owns request building and execution.
 */
final class SlackActionCatalog {

    private SlackActionCatalog() {}

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    static List<IntegrationAction> actions() {
        return List.of(
                new IntegrationAction("post_message",
                        "Nachricht in einen Kanal senden (chat.postMessage) — die App muss Mitglied des Kanals sein",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "channel", str("Kanal-ID (z.B. C0123456789) oder Kanalname"),
                                        "text", str("Nachrichtentext (mrkdwn); bei 'blocks' der Fallback-Text für Benachrichtigungen"),
                                        "blocks", Map.of("type", "array",
                                                "description", "Optional: Block-Kit-Blöcke (JSON-Array oder JSON-Text)"),
                                        "thread_ts", str("Optional: 'ts' der Elternnachricht, um im Thread zu antworten")),
                                "required", List.of("channel")),
                        List.of("chat:write")),
                new IntegrationAction("list_channels",
                        "Kanäle auflisten (conversations.list, seitenweise über 'cursor')",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "types", Map.of("type", "string", "default", "public_channel",
                                                "description", "Kommagetrennt: public_channel, private_channel, mpim, im (private brauchen groups:read)"),
                                        "limit", Map.of("type", "integer", "default", 100, "description", "Einträge pro Seite (1–1000)"),
                                        "cursor", str("'response_metadata.next_cursor' der vorigen Seite"))),
                        List.of("channels:read")),
                new IntegrationAction("get_channel_history",
                        "Nachrichten eines Kanals lesen (conversations.history, neueste zuerst)",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "channel", str("Kanal-ID (z.B. C0123456789)"),
                                        "limit", Map.of("type", "integer", "default", 100, "description", "Nachrichten pro Seite (1–999)"),
                                        "cursor", str("'response_metadata.next_cursor' der vorigen Seite"),
                                        "oldest", str("Nur Nachrichten nach diesem Zeitstempel (Unix, z.B. 1503435956.000247)"),
                                        "latest", str("Nur Nachrichten vor diesem Zeitstempel")),
                                "required", List.of("channel")),
                        List.of("channels:history"))
        );
    }
}
