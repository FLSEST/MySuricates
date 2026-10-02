package com.automation.service.integration.providers.google;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.automation.service.integration.providers.IntegrationParams;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleGmailProvider implements IntegrationProvider {

    private static final String BASE_URL = "https://gmail.googleapis.com/gmail/v1/users/me";
    private static final String GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String getProviderId() {
        return "google-gmail";
    }

    @Override
    public String getDisplayName() {
        return "Gmail";
    }

    @Override
    public String getIcon() {
        return "Mail";
    }

    @Override
    public String getDescription() {
        return "Gmail-Nachrichten lesen und fuer Workflows auswerten";
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        return new OAuthConfig(
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                List.of(GMAIL_READONLY_SCOPE),
                "GOOGLE_CLIENT_ID",
                "GOOGLE_CLIENT_SECRET",
                Map.of("access_type", "offline", "prompt", "consent")
        ).withRevocationUrl(OAuthConfig.GOOGLE_REVOCATION_URL);
    }

    @Override
    public String fetchAccountInfo(String accessToken) {
        try {
            JsonNode profile = httpGet(BASE_URL + "/profile", accessToken);
            return objectMapper.writeValueAsString(Map.of(
                    "email", profile.path("emailAddress").asText(""),
                    "messagesTotal", profile.path("messagesTotal").asLong(0),
                    "threadsTotal", profile.path("threadsTotal").asLong(0)
            ));
        } catch (Exception e) {
            log.warn("Failed to fetch Gmail account info: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
        return List.of(
                new IntegrationAction(
                        "list_messages",
                        "Gmail-Nachrichten als kompakte Liste abrufen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "maxResults", Map.of("type", "integer", "default", 10, "description", "Maximale Anzahl Nachrichten"),
                                        "query", Map.of("type", "string", "description", "Gmail-Suchsyntax, z.B. in:inbox newer_than:7d"),
                                        "labelIds", Map.of("type", "array", "description", "Optionale Gmail-Labels, z.B. [INBOX, UNREAD]")
                                )
                        ),
                        List.of(GMAIL_READONLY_SCOPE)
                ),
                new IntegrationAction(
                        "get_message",
                        "Eine einzelne Gmail-Nachricht mit Metadaten und Inhalt abrufen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "messageId", Map.of("type", "string", "description", "Gmail Message ID"),
                                        "format", Map.of("type", "string", "default", "full", "description", "Gmail-Format: full, metadata, minimal")
                                ),
                                "required", List.of("messageId")
                        ),
                        List.of(GMAIL_READONLY_SCOPE)
                )
        );
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        return switch (action) {
            case "list_messages" -> listMessages(params, accessToken);
            case "get_message" -> getMessage(params, accessToken);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private Object listMessages(Map<String, Object> params, String accessToken) {
        StringBuilder url = new StringBuilder(BASE_URL)
                .append("/messages?maxResults=")
                .append(IntegrationParams.intParam(params, "maxResults", 10));

        String query = paramString(params, "query", null);
        if (query != null && !query.isBlank()) {
            url.append("&q=").append(urlEncode(query));
        }

        for (String labelId : paramStringList(params, "labelIds")) {
            url.append("&labelIds=").append(urlEncode(labelId));
        }

        JsonNode listResponse = httpGet(url.toString(), accessToken);
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode messageRef : listResponse.path("messages")) {
            String messageId = messageRef.path("id").asText(null);
            if (messageId == null || messageId.isBlank()) {
                continue;
            }
            JsonNode message = httpGet(metadataUrl(messageId), accessToken);
            items.add(normalizeMessageSummary(message));
        }

        return Map.of(
                "messages", items,
                "resultSizeEstimate", listResponse.path("resultSizeEstimate").asInt(items.size())
        );
    }

    private Object getMessage(Map<String, Object> params, String accessToken) {
        String messageId = requireParam(params, "messageId");
        String format = paramString(params, "format", "full");
        JsonNode message = httpGet(BASE_URL + "/messages/" + urlEncode(messageId) + "?format=" + urlEncode(format), accessToken);
        return normalizeFullMessage(message, format);
    }

    private JsonNode httpGet(String url, String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return objectMapper.readTree(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Gmail API request failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> normalizeMessageSummary(JsonNode message) {
        Map<String, String> headers = extractHeaders(message.path("payload"));
        List<String> labels = stringValues(message.path("labelIds"));
        String messageId = message.path("id").asText("");
        return Map.of(
                "provider", "gmail",
                "id", messageId,
                "threadId", message.path("threadId").asText(""),
                "subject", headers.getOrDefault("subject", ""),
                "from", headers.getOrDefault("from", ""),
                "date", formatInternalDate(message.path("internalDate").asText(null)),
                "snippet", message.path("snippet").asText(""),
                "labelIds", labels,
                "isUnread", labels.contains("UNREAD"),
                "link", "https://mail.google.com/mail/u/0/#inbox/" + messageId
        );
    }

    private Map<String, Object> normalizeFullMessage(JsonNode message, String format) {
        Map<String, String> headers = extractHeaders(message.path("payload"));
        List<String> labels = stringValues(message.path("labelIds"));
        String messageId = message.path("id").asText("");

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("provider", "gmail");
        normalized.put("id", messageId);
        normalized.put("threadId", message.path("threadId").asText(""));
        normalized.put("format", format);
        normalized.put("subject", headers.getOrDefault("subject", ""));
        normalized.put("from", headers.getOrDefault("from", ""));
        normalized.put("to", headers.getOrDefault("to", ""));
        normalized.put("date", formatInternalDate(message.path("internalDate").asText(null)));
        normalized.put("snippet", message.path("snippet").asText(""));
        normalized.put("labelIds", labels);
        normalized.put("isUnread", labels.contains("UNREAD"));
        normalized.put("headers", headers);
        normalized.put("plainTextBody", extractBody(message.path("payload"), "text/plain"));
        normalized.put("htmlBody", extractBody(message.path("payload"), "text/html"));
        normalized.put("link", "https://mail.google.com/mail/u/0/#inbox/" + messageId);
        return normalized;
    }

    private static Map<String, String> extractHeaders(JsonNode payload) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (JsonNode header : payload.path("headers")) {
            String name = header.path("name").asText("");
            if (!name.isBlank()) {
                headers.put(name.toLowerCase(Locale.ROOT), header.path("value").asText(""));
            }
        }
        return headers;
    }

    private static List<String> stringValues(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode child : node) {
            values.add(child.asText());
        }
        return values;
    }

    private static String extractBody(JsonNode payload, String mimeType) {
        if (payload.isMissingNode() || payload.isNull()) {
            return null;
        }

        if (mimeType.equalsIgnoreCase(payload.path("mimeType").asText()) && payload.path("body").hasNonNull("data")) {
            return decodeBase64Url(payload.path("body").path("data").asText());
        }

        for (JsonNode part : payload.path("parts")) {
            String value = extractBody(part, mimeType);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String decodeBase64Url(String encoded) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String metadataUrl(String messageId) {
        return BASE_URL + "/messages/" + urlEncode(messageId)
                + "?format=metadata&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=To&metadataHeaders=Date";
    }

    private static void checkResponse(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Gmail API error " + response.statusCode() + ": " + response.body());
        }
    }

    private static String requireParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null || val.toString().isBlank()) throw IntegrationParams.missing(key);
        return val.toString();
    }

    private static String paramString(Map<String, Object> params, String key, String defaultValue) {
        Object val = params.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    private static List<String> paramStringList(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(Object::toString).filter(s -> !s.isBlank()).toList();
    }

    private static String formatInternalDate(String internalDate) {
        if (internalDate == null || internalDate.isBlank()) {
            return null;
        }
        try {
            long millis = Long.parseLong(internalDate);
            return Instant.ofEpochMilli(millis)
                    .atZone(ZoneId.systemDefault())
                    .format(ISO_OFFSET);
        } catch (NumberFormatException e) {
            return internalDate;
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
