package com.automation.service.integration.providers.confluence;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class ConfluenceProvider implements IntegrationProvider {

    private static final String ATLASSIAN_API = "https://api.atlassian.com";

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ConcurrentHashMap<String, String> cloudIdCache = new ConcurrentHashMap<>();

    @Override
    public String getProviderId() {
        return "confluence";
    }

    @Override
    public String getDisplayName() {
        return "Confluence";
    }

    @Override
    public String getIcon() {
        return "BookOpen";
    }

    @Override
    public String getDescription() {
        return "Confluence-Seiten und Spaces lesen, durchsuchen und bearbeiten";
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        return new OAuthConfig(
                "https://auth.atlassian.com/authorize",
                "https://auth.atlassian.com/oauth/token",
                List.of("read:space:confluence", "read:content:confluence",
                        "read:content-details:confluence", "search:confluence", "offline_access"),
                List.of("write:content:confluence"),
                "CONFLUENCE_CLIENT_ID",
                "CONFLUENCE_CLIENT_SECRET",
                Map.of("audience", "api.atlassian.com", "prompt", "consent")
        );
    }

    @Override
    public String fetchAccountInfo(String accessToken) {
        try {
            String cloudId = resolveCloudId(accessToken);
            if (cloudId == null) return null;

            // Use the Atlassian user endpoint
            JsonNode user = httpGet(ATLASSIAN_API + "/me", accessToken, JsonNode.class);

            return objectMapper.writeValueAsString(Map.of(
                    "cloudId", cloudId,
                    "name", user.has("display_name") ? user.get("display_name").asText() : "",
                    "email", user.has("email") ? user.get("email").asText() : ""
            ));
        } catch (Exception e) {
            log.warn("Failed to fetch Confluence account info: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
        return List.of(
                new IntegrationAction("list_spaces", "Alle zugänglichen Spaces auflisten",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "limit", Map.of("type", "integer", "default", 25, "description", "Max. Ergebnisse"),
                                        "type", Map.of("type", "string", "description", "Space-Typ: global, personal (Standard: alle)")
                                )
                        )),
                new IntegrationAction("list_pages", "Seiten eines Spaces auflisten",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "spaceId", Map.of("type", "string", "description", "Space-ID"),
                                        "limit", Map.of("type", "integer", "default", 25, "description", "Max. Ergebnisse"),
                                        "status", Map.of("type", "string", "default", "current", "description", "Status: current, draft, archived")
                                ),
                                "required", List.of("spaceId")
                        )),
                new IntegrationAction("get_page", "Eine einzelne Seite abrufen (inkl. Inhalt)",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "pageId", Map.of("type", "string", "description", "Seiten-ID")
                                ),
                                "required", List.of("pageId")
                        )),
                new IntegrationAction("search", "Inhalte per CQL durchsuchen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "cql", Map.of("type", "string", "description", "CQL-Query (z.B. 'type = page AND space = MYSPACE AND text ~ \"suchbegriff\"')"),
                                        "limit", Map.of("type", "integer", "default", 20, "description", "Max. Ergebnisse")
                                ),
                                "required", List.of("cql")
                        )),
                new IntegrationAction("create_page", "Eine neue Seite erstellen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "spaceId", Map.of("type", "string", "description", "Space-ID"),
                                        "title", Map.of("type", "string", "description", "Seitentitel"),
                                        "body", Map.of("type", "string", "description", "Seiteninhalt (HTML oder Klartext)"),
                                        "parentId", Map.of("type", "string", "description", "Übergeordnete Seiten-ID (optional)")
                                ),
                                "required", List.of("spaceId", "title")
                        ), List.of("write:content:confluence")),
                new IntegrationAction("update_page", "Eine bestehende Seite aktualisieren",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "pageId", Map.of("type", "string", "description", "Seiten-ID"),
                                        "title", Map.of("type", "string", "description", "Neuer Titel"),
                                        "body", Map.of("type", "string", "description", "Neuer Inhalt (HTML oder Klartext)"),
                                        "version", Map.of("type", "integer", "description", "Aktuelle Versionsnummer (wird um 1 erhöht)")
                                ),
                                "required", List.of("pageId", "version")
                        ), List.of("write:content:confluence"))
        );
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        return switch (action) {
            case "list_spaces" -> listSpaces(params, accessToken);
            case "list_pages" -> listPages(params, accessToken);
            case "get_page" -> getPage(params, accessToken);
            case "search" -> search(params, accessToken);
            case "create_page" -> createPage(params, accessToken);
            case "update_page" -> updatePage(params, accessToken);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    // --- Action implementations ---

    private Object listSpaces(Map<String, Object> params, String accessToken) {
        String baseUrl = resolveBaseUrl(accessToken);
        int limit = paramInt(params, "limit", 25);
        String url = baseUrl + "/spaces?limit=" + limit;
        if (params.containsKey("type")) {
            url += "&type=" + urlEncode(params.get("type").toString());
        }
        return httpGet(url, accessToken, Object.class);
    }

    private Object listPages(Map<String, Object> params, String accessToken) {
        String spaceId = requireParam(params, "spaceId");
        String baseUrl = resolveBaseUrl(accessToken);
        int limit = paramInt(params, "limit", 25);
        String status = paramString(params, "status", "current");
        String url = baseUrl + "/spaces/" + urlEncode(spaceId) + "/pages?limit=" + limit + "&status=" + status;
        return httpGet(url, accessToken, Object.class);
    }

    private Object getPage(Map<String, Object> params, String accessToken) {
        String pageId = requireParam(params, "pageId");
        String baseUrl = resolveBaseUrl(accessToken);
        String url = baseUrl + "/pages/" + urlEncode(pageId) + "?body-format=storage";
        return httpGet(url, accessToken, Object.class);
    }

    private Object search(Map<String, Object> params, String accessToken) {
        String cql = requireParam(params, "cql");
        String v1BaseUrl = resolveV1BaseUrl(accessToken);
        int limit = paramInt(params, "limit", 20);
        String url = v1BaseUrl + "/search?cql=" + urlEncode(cql) + "&limit=" + limit;
        return httpGet(url, accessToken, Object.class);
    }

    private Object createPage(Map<String, Object> params, String accessToken) {
        String spaceId = requireParam(params, "spaceId");
        String title = requireParam(params, "title");
        String baseUrl = resolveBaseUrl(accessToken);

        Map<String, Object> page = new java.util.HashMap<>();
        page.put("spaceId", spaceId);
        page.put("title", title);
        page.put("status", "current");

        if (params.containsKey("parentId")) {
            page.put("parentId", params.get("parentId").toString());
        }

        if (params.containsKey("body")) {
            page.put("body", Map.of(
                    "representation", "storage",
                    "value", params.get("body").toString()
            ));
        }

        return httpPost(baseUrl + "/pages", page, accessToken);
    }

    private Object updatePage(Map<String, Object> params, String accessToken) {
        String pageId = requireParam(params, "pageId");
        int version = paramInt(params, "version", 1);
        String baseUrl = resolveBaseUrl(accessToken);

        Map<String, Object> update = new java.util.HashMap<>();
        update.put("id", pageId);
        update.put("status", "current");
        update.put("version", Map.of("number", version + 1));

        if (params.containsKey("title")) {
            update.put("title", params.get("title").toString());
        }
        if (params.containsKey("body")) {
            update.put("body", Map.of(
                    "representation", "storage",
                    "value", params.get("body").toString()
            ));
        }

        return httpPut(baseUrl + "/pages/" + urlEncode(pageId), update, accessToken);
    }

    // --- Cloud ID resolution (same as Jira, shared Atlassian OAuth) ---

    private String resolveBaseUrl(String accessToken) {
        String cloudId = resolveCloudId(accessToken);
        if (cloudId == null) {
            throw new IllegalStateException("Could not resolve Confluence Cloud ID. Is the site accessible?");
        }
        return ATLASSIAN_API + "/ex/confluence/" + cloudId + "/wiki/api/v2";
    }

    private String resolveV1BaseUrl(String accessToken) {
        String cloudId = resolveCloudId(accessToken);
        if (cloudId == null) {
            throw new IllegalStateException("Could not resolve Confluence Cloud ID. Is the site accessible?");
        }
        return ATLASSIAN_API + "/ex/confluence/" + cloudId + "/wiki/rest/api";
    }

    private String resolveCloudId(String accessToken) {
        return cloudIdCache.computeIfAbsent(accessToken, token -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(ATLASSIAN_API + "/oauth/token/accessible-resources"))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    log.error("Failed to resolve Confluence Cloud ID: {} {}", response.statusCode(), response.body());
                    return null;
                }

                JsonNode resources = objectMapper.readTree(response.body());
                if (resources.isArray() && !resources.isEmpty()) {
                    return resources.get(0).get("id").asText();
                }
                log.warn("No accessible Confluence resources found");
                return null;
            } catch (Exception e) {
                log.error("Failed to resolve Confluence Cloud ID", e);
                return null;
            }
        });
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
            throw new IllegalStateException("Confluence API request failed: " + e.getMessage(), e);
        }
    }

    private Object httpPost(String url, Object body, String accessToken) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return objectMapper.readValue(response.body(), Object.class);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Confluence API request failed: " + e.getMessage(), e);
        }
    }

    private Object httpPut(String url, Object body, String accessToken) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return objectMapper.readValue(response.body(), Object.class);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Confluence API request failed: " + e.getMessage(), e);
        }
    }

    private void checkResponse(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Confluence API error " + response.statusCode() + ": " + response.body());
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
