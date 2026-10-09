package com.automation.service.integration.providers.confluence;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.automation.service.integration.OutboundClient;
import com.automation.service.integration.OutboundHttp;
import com.automation.service.integration.providers.IntegrationParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.automation.service.integration.providers.IntegrationParams.paramString;
import static com.automation.service.integration.providers.IntegrationParams.requireParam;
import static com.automation.service.integration.providers.IntegrationParams.urlEncode;

@Component
@RequiredArgsConstructor
@Slf4j
public class ConfluenceProvider implements IntegrationProvider {

    private static final String ATLASSIAN_API = "https://api.atlassian.com";

    private final ObjectMapper objectMapper;

    private final OutboundClient httpClient = OutboundHttp.fixedHost(Duration.ofSeconds(10));

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
                List.of("read:confluence-content.all", "read:confluence-space.summary",
                        "search:confluence", "offline_access"),
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
                                        "spaceKey", Map.of("type", "string", "description", "Space-Key (z.B. MYSPACE)"),
                                        "limit", Map.of("type", "integer", "default", 25, "description", "Max. Ergebnisse")
                                ),
                                "required", List.of("spaceKey")
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
                        ))
                // Note: create_page and update_page removed — Confluence v1 content endpoints
                // return 401 with classic OAuth scopes, and v2 requires granular scopes not
                // available in Atlassian Developer Console for OAuth 2.0 (3LO) apps.
        );
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        return switch (action) {
            case "list_spaces" -> listSpaces(params, accessToken);
            case "list_pages" -> listPages(params, accessToken);
            case "get_page" -> getPage(params, accessToken);
            case "search" -> search(params, accessToken);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    // --- Action implementations ---

    private Object listSpaces(Map<String, Object> params, String accessToken) {
        int limit = IntegrationParams.intParam(params, "limit", 25); // before the cloud-id request
        String baseUrl = resolveV1BaseUrl(accessToken);
        String cql = "type = space";
        if (params.containsKey("type")) {
            cql += " AND space.type = " + urlEncode(params.get("type").toString());
        }
        String url = baseUrl + "/search?cql=" + urlEncode(cql) + "&limit=" + limit;
        return httpGet(url, accessToken, Object.class);
    }

    private Object listPages(Map<String, Object> params, String accessToken) {
        String spaceKey = requireParam(params, "spaceKey");
        int limit = IntegrationParams.intParam(params, "limit", 25);
        String baseUrl = resolveV1BaseUrl(accessToken);
        String cql = "type = page AND space = " + spaceKey;
        String url = baseUrl + "/search?cql=" + urlEncode(cql) + "&limit=" + limit;
        return httpGet(url, accessToken, Object.class);
    }

    private Object getPage(Map<String, Object> params, String accessToken) {
        String pageId = requireParam(params, "pageId");
        String baseUrl = resolveV1BaseUrl(accessToken);
        String cql = "type = page AND id = " + pageId;
        String url = baseUrl + "/search?cql=" + urlEncode(cql) + "&expand=content.body.storage,content.version";
        return httpGet(url, accessToken, Object.class);
    }

    private Object search(Map<String, Object> params, String accessToken) {
        String cql = requireParam(params, "cql");
        int limit = IntegrationParams.intParam(params, "limit", 20);
        String v1BaseUrl = resolveV1BaseUrl(accessToken);
        String url = v1BaseUrl + "/search?cql=" + urlEncode(cql) + "&limit=" + limit;
        return httpGet(url, accessToken, Object.class);
    }


    // --- Cloud ID resolution (same as Jira, shared Atlassian OAuth) ---

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

    private void checkResponse(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Confluence API error " + response.statusCode() + ": " + response.body());
        }
    }
}
