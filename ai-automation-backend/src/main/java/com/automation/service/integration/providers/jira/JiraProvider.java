package com.automation.service.integration.providers.jira;

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
public class JiraProvider implements IntegrationProvider {

    private static final String ATLASSIAN_API = "https://api.atlassian.com";

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Cache cloudId per access token to avoid resolving on every action */
    private final ConcurrentHashMap<String, String> cloudIdCache = new ConcurrentHashMap<>();

    @Override
    public String getProviderId() {
        return "jira";
    }

    @Override
    public String getDisplayName() {
        return "Jira";
    }

    @Override
    public String getIcon() {
        return "TicketCheck";
    }

    @Override
    public String getDescription() {
        return "Jira-Issues erstellen, aktualisieren und durchsuchen";
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        return new OAuthConfig(
                "https://auth.atlassian.com/authorize",
                "https://auth.atlassian.com/oauth/token",
                List.of("read:jira-work", "write:jira-work", "read:jira-user", "offline_access"),
                "JIRA_CLIENT_ID",
                "JIRA_CLIENT_SECRET",
                Map.of("audience", "api.atlassian.com", "prompt", "consent")
        );
    }

    @Override
    public String fetchAccountInfo(String accessToken) {
        try {
            String cloudId = resolveCloudId(accessToken);
            if (cloudId == null) return null;

            String baseUrl = buildBaseUrl(cloudId);
            JiraUser user = httpGet(baseUrl + "/myself", accessToken, JiraUser.class);

            return objectMapper.writeValueAsString(Map.of(
                    "cloudId", cloudId,
                    "name", user.displayName() != null ? user.displayName() : "",
                    "email", user.emailAddress() != null ? user.emailAddress() : ""
            ));
        } catch (Exception e) {
            log.warn("Failed to fetch Jira account info: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
        return List.of(
                new IntegrationAction("list_projects", "Alle zugänglichen Projekte auflisten",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "maxResults", Map.of("type", "integer", "default", 50, "description", "Max. Ergebnisse"),
                                        "startAt", Map.of("type", "integer", "default", 0, "description", "Offset für Paginierung")
                                )
                        )),
                new IntegrationAction("search_issues", "Issues per JQL durchsuchen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "jql", Map.of("type", "string", "description", "JQL-Query (z.B. 'project = MYPROJ AND status = Open')"),
                                        "maxResults", Map.of("type", "integer", "default", 20, "description", "Max. Ergebnisse"),
                                        "startAt", Map.of("type", "integer", "default", 0, "description", "Offset"),
                                        "fields", Map.of("type", "string", "description", "Felder (kommagetrennt, z.B. 'summary,status,assignee')")
                                ),
                                "required", List.of("jql")
                        )),
                new IntegrationAction("get_issue", "Ein einzelnes Issue abrufen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "issueKey", Map.of("type", "string", "description", "Issue-Key (z.B. PROJ-123)")
                                ),
                                "required", List.of("issueKey")
                        )),
                new IntegrationAction("create_issue", "Ein neues Issue erstellen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "projectKey", Map.of("type", "string", "description", "Projekt-Key (z.B. PROJ)"),
                                        "summary", Map.of("type", "string", "description", "Titel des Issues"),
                                        "issueType", Map.of("type", "string", "default", "Task", "description", "Issue-Typ (Task, Bug, Story, Epic)"),
                                        "description", Map.of("type", "string", "description", "Beschreibung (Klartext)"),
                                        "priority", Map.of("type", "string", "description", "Priorität (Highest, High, Medium, Low, Lowest)"),
                                        "assigneeId", Map.of("type", "string", "description", "Account-ID des Bearbeiters"),
                                        "labels", Map.of("type", "string", "description", "Labels (kommagetrennt)")
                                ),
                                "required", List.of("projectKey", "summary")
                        )),
                new IntegrationAction("update_issue", "Ein Issue aktualisieren",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "issueKey", Map.of("type", "string", "description", "Issue-Key (z.B. PROJ-123)"),
                                        "summary", Map.of("type", "string", "description", "Neuer Titel"),
                                        "description", Map.of("type", "string", "description", "Neue Beschreibung"),
                                        "priority", Map.of("type", "string", "description", "Neue Priorität"),
                                        "assigneeId", Map.of("type", "string", "description", "Neuer Bearbeiter (Account-ID)"),
                                        "labels", Map.of("type", "string", "description", "Labels (kommagetrennt, ersetzt bestehende)")
                                ),
                                "required", List.of("issueKey")
                        )),
                new IntegrationAction("add_comment", "Einen Kommentar zu einem Issue hinzufügen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "issueKey", Map.of("type", "string", "description", "Issue-Key (z.B. PROJ-123)"),
                                        "body", Map.of("type", "string", "description", "Kommentartext")
                                ),
                                "required", List.of("issueKey", "body")
                        )),
                new IntegrationAction("get_transitions", "Verfügbare Status-Übergänge eines Issues abrufen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "issueKey", Map.of("type", "string", "description", "Issue-Key (z.B. PROJ-123)")
                                ),
                                "required", List.of("issueKey")
                        )),
                new IntegrationAction("transition_issue", "Status-Übergang eines Issues ausführen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "issueKey", Map.of("type", "string", "description", "Issue-Key (z.B. PROJ-123)"),
                                        "transitionId", Map.of("type", "string", "description", "Transition-ID (aus get_transitions)")
                                ),
                                "required", List.of("issueKey", "transitionId")
                        ))
        );
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        return switch (action) {
            case "list_projects" -> listProjects(params, accessToken);
            case "search_issues" -> searchIssues(params, accessToken);
            case "get_issue" -> getIssue(params, accessToken);
            case "create_issue" -> createIssue(params, accessToken);
            case "update_issue" -> updateIssue(params, accessToken);
            case "add_comment" -> addComment(params, accessToken);
            case "get_transitions" -> getTransitions(params, accessToken);
            case "transition_issue" -> transitionIssue(params, accessToken);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private Object listProjects(Map<String, Object> params, String accessToken) {
        String baseUrl = resolveBaseUrl(accessToken);
        int maxResults = paramInt(params, "maxResults", 50);
        int startAt = paramInt(params, "startAt", 0);
        String url = baseUrl + "/project/search?maxResults=" + maxResults + "&startAt=" + startAt;
        return httpGet(url, accessToken, Object.class);
    }

    private Object searchIssues(Map<String, Object> params, String accessToken) {
        String jql = requireParam(params, "jql");
        String baseUrl = resolveBaseUrl(accessToken);
        int maxResults = paramInt(params, "maxResults", 20);
        int startAt = paramInt(params, "startAt", 0);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("jql", jql);
        body.put("maxResults", maxResults);
        body.put("startAt", startAt);

        if (params.containsKey("fields")) {
            body.put("fields", List.of(params.get("fields").toString().split(",")));
        }

        return httpPost(baseUrl + "/search", body, accessToken, JiraSearchResult.class);
    }

    private Object getIssue(Map<String, Object> params, String accessToken) {
        String issueKey = requireParam(params, "issueKey");
        String baseUrl = resolveBaseUrl(accessToken);
        return httpGet(baseUrl + "/issue/" + urlEncode(issueKey), accessToken, JiraIssue.class);
    }

    private Object createIssue(Map<String, Object> params, String accessToken) {
        String projectKey = requireParam(params, "projectKey");
        String summary = requireParam(params, "summary");
        String baseUrl = resolveBaseUrl(accessToken);
        String issueType = paramString(params, "issueType", "Task");

        Map<String, Object> fields = new java.util.HashMap<>();
        fields.put("project", Map.of("key", projectKey));
        fields.put("summary", summary);
        fields.put("issuetype", Map.of("name", issueType));

        if (params.containsKey("description")) {
            fields.put("description", buildAdfText(params.get("description").toString()));
        }
        if (params.containsKey("priority")) {
            fields.put("priority", Map.of("name", params.get("priority").toString()));
        }
        if (params.containsKey("assigneeId")) {
            fields.put("assignee", Map.of("accountId", params.get("assigneeId").toString()));
        }
        if (params.containsKey("labels")) {
            fields.put("labels", List.of(params.get("labels").toString().split(",")));
        }

        return httpPost(baseUrl + "/issue", Map.of("fields", fields), accessToken, JiraIssue.class);
    }

    private Object updateIssue(Map<String, Object> params, String accessToken) {
        String issueKey = requireParam(params, "issueKey");
        String baseUrl = resolveBaseUrl(accessToken);

        Map<String, Object> fields = new java.util.HashMap<>();
        if (params.containsKey("summary")) fields.put("summary", params.get("summary"));
        if (params.containsKey("description")) {
            fields.put("description", buildAdfText(params.get("description").toString()));
        }
        if (params.containsKey("priority")) {
            fields.put("priority", Map.of("name", params.get("priority").toString()));
        }
        if (params.containsKey("assigneeId")) {
            fields.put("assignee", Map.of("accountId", params.get("assigneeId").toString()));
        }
        if (params.containsKey("labels")) {
            fields.put("labels", List.of(params.get("labels").toString().split(",")));
        }

        httpPut(baseUrl + "/issue/" + urlEncode(issueKey), Map.of("fields", fields), accessToken);
        return Map.of("updated", true, "issueKey", issueKey);
    }

    private Object addComment(Map<String, Object> params, String accessToken) {
        String issueKey = requireParam(params, "issueKey");
        String body = requireParam(params, "body");
        String baseUrl = resolveBaseUrl(accessToken);

        Map<String, Object> comment = Map.of("body", buildAdfText(body));
        return httpPost(baseUrl + "/issue/" + urlEncode(issueKey) + "/comment", comment, accessToken, JiraComment.class);
    }

    private Object getTransitions(Map<String, Object> params, String accessToken) {
        String issueKey = requireParam(params, "issueKey");
        String baseUrl = resolveBaseUrl(accessToken);
        return httpGet(baseUrl + "/issue/" + urlEncode(issueKey) + "/transitions", accessToken, Object.class);
    }

    private Object transitionIssue(Map<String, Object> params, String accessToken) {
        String issueKey = requireParam(params, "issueKey");
        String transitionId = requireParam(params, "transitionId");
        String baseUrl = resolveBaseUrl(accessToken);

        Map<String, Object> body = Map.of("transition", Map.of("id", transitionId));
        httpPostNoResponse(baseUrl + "/issue/" + urlEncode(issueKey) + "/transitions", body, accessToken);
        return Map.of("transitioned", true, "issueKey", issueKey, "transitionId", transitionId);
    }

    // --- Cloud ID resolution ---

    private String resolveBaseUrl(String accessToken) {
        String cloudId = resolveCloudId(accessToken);
        if (cloudId == null) {
            throw new IllegalStateException("Could not resolve Jira Cloud ID. Is the Jira site accessible?");
        }
        return buildBaseUrl(cloudId);
    }

    private String buildBaseUrl(String cloudId) {
        return ATLASSIAN_API + "/ex/jira/" + cloudId + "/rest/api/3";
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
                    log.error("Failed to resolve Jira Cloud ID: {} {}", response.statusCode(), response.body());
                    return null;
                }

                JsonNode resources = objectMapper.readTree(response.body());
                if (resources.isArray() && !resources.isEmpty()) {
                    return resources.get(0).get("id").asText();
                }
                log.warn("No accessible Jira resources found");
                return null;
            } catch (Exception e) {
                log.error("Failed to resolve Jira Cloud ID", e);
                return null;
            }
        });
    }

    // --- ADF (Atlassian Document Format) helper ---

    private Map<String, Object> buildAdfText(String text) {
        return Map.of(
                "type", "doc",
                "version", 1,
                "content", List.of(Map.of(
                        "type", "paragraph",
                        "content", List.of(Map.of(
                                "type", "text",
                                "text", text
                        ))
                ))
        );
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
            throw new IllegalStateException("Jira API request failed: " + e.getMessage(), e);
        }
    }

    private <T> T httpPost(String url, Object body, String accessToken, Class<T> responseType) {
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
            return objectMapper.readValue(response.body(), responseType);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Jira API request failed: " + e.getMessage(), e);
        }
    }

    private void httpPostNoResponse(String url, Object body, String accessToken) {
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
            if (response.statusCode() >= 400) {
                checkResponse(response);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Jira API request failed: " + e.getMessage(), e);
        }
    }

    private void httpPut(String url, Object body, String accessToken) {
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
            if (response.statusCode() >= 400) {
                checkResponse(response);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Jira API request failed: " + e.getMessage(), e);
        }
    }

    private void checkResponse(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Jira API error " + response.statusCode() + ": " + response.body());
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
