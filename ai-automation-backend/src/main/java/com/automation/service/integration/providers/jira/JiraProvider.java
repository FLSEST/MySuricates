package com.automation.service.integration.providers.jira;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.automation.service.integration.TaskExportCapable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.automation.service.integration.providers.jira.JiraParams.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class JiraProvider implements IntegrationProvider, TaskExportCapable {

    private static final String ATLASSIAN_API = "https://api.atlassian.com";

    private final ObjectMapper objectMapper;
    private final JiraHttpClient http;

    /** Cache the accessible site (cloudId + browse URL) per access token to avoid resolving on every action */
    private final ConcurrentHashMap<String, JiraSite> siteCache = new ConcurrentHashMap<>();

    private record JiraSite(String cloudId, String siteUrl) {}

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
                List.of("read:jira-work", "read:jira-user", "offline_access"),
                List.of("write:jira-work"),
                "JIRA_CLIENT_ID",
                "JIRA_CLIENT_SECRET",
                Map.of("audience", "api.atlassian.com", "prompt", "consent")
        );
    }

    @Override
    public String fetchAccountInfo(String accessToken) {
        try {
            JiraSite site = resolveSite(accessToken);
            if (site == null) return null;
            String cloudId = site.cloudId();

            String baseUrl = buildBaseUrl(cloudId);
            JiraUser user = http.get(baseUrl + "/myself", accessToken, JiraUser.class);

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
        return JiraActionCatalog.actions();
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
        return http.get(url, accessToken, Object.class);
    }

    private Object searchIssues(Map<String, Object> params, String accessToken) {
        String jql = requireParam(params, "jql");
        String baseUrl = resolveBaseUrl(accessToken);
        int maxResults = paramInt(params, "maxResults", 20);
        int startAt = paramInt(params, "startAt", 0);
        String fields = paramString(params, "fields", "summary,status,assignee,priority,updated,created");

        String url = baseUrl + "/search/jql?jql=" + urlEncode(jql)
                + "&maxResults=" + maxResults + "&startAt=" + startAt
                + "&fields=" + urlEncode(fields);

        return http.get(url, accessToken, JiraSearchResult.class);
    }

    private Object getIssue(Map<String, Object> params, String accessToken) {
        String issueKey = requireParam(params, "issueKey");
        String baseUrl = resolveBaseUrl(accessToken);
        return http.get(baseUrl + "/issue/" + urlEncode(issueKey), accessToken, JiraIssue.class);
    }

    private Object createIssue(Map<String, Object> params, String accessToken) {
        String projectKey = requireParam(params, "projectKey");
        String summary = requireParam(params, "summary");
        String baseUrl = resolveBaseUrl(accessToken);
        String issueType = paramString(params, "issueType", "Task");

        Map<String, Object> fields = new HashMap<>();
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

        return http.post(baseUrl + "/issue", Map.of("fields", fields), accessToken, JiraIssue.class);
    }

    private Object updateIssue(Map<String, Object> params, String accessToken) {
        String issueKey = requireParam(params, "issueKey");
        String baseUrl = resolveBaseUrl(accessToken);

        Map<String, Object> fields = new HashMap<>();
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

        http.put(baseUrl + "/issue/" + urlEncode(issueKey), Map.of("fields", fields), accessToken);
        return Map.of("updated", true, "issueKey", issueKey);
    }

    private Object addComment(Map<String, Object> params, String accessToken) {
        String issueKey = requireParam(params, "issueKey");
        String body = requireParam(params, "body");
        String baseUrl = resolveBaseUrl(accessToken);

        Map<String, Object> comment = Map.of("body", buildAdfText(body));
        return http.post(baseUrl + "/issue/" + urlEncode(issueKey) + "/comment", comment, accessToken, JiraComment.class);
    }

    private Object getTransitions(Map<String, Object> params, String accessToken) {
        String issueKey = requireParam(params, "issueKey");
        String baseUrl = resolveBaseUrl(accessToken);
        return http.get(baseUrl + "/issue/" + urlEncode(issueKey) + "/transitions", accessToken, Object.class);
    }

    private Object transitionIssue(Map<String, Object> params, String accessToken) {
        String issueKey = requireParam(params, "issueKey");
        String transitionId = requireParam(params, "transitionId");
        String baseUrl = resolveBaseUrl(accessToken);

        Map<String, Object> body = Map.of("transition", Map.of("id", transitionId));
        http.postNoResponse(baseUrl + "/issue/" + urlEncode(issueKey) + "/transitions", body, accessToken);
        return Map.of("transitioned", true, "issueKey", issueKey, "transitionId", transitionId);
    }

    // --- TaskExportCapable (integrations-agnostischer Single-TODO-Export, target = Project-Key) ---

    @Override
    public TaskRef createTask(TaskExportInput input, String accessToken) {
        String projectKey = JiraTaskExporter.requireProjectKey(input.target());
        JiraSite site = requireSite(accessToken);
        return JiraTaskExporter.createTask(http, buildBaseUrl(site.cloudId()), site.siteUrl(), projectKey, input, accessToken);
    }

    @Override
    public List<TaskTarget> listTargets(String accessToken) {
        return JiraTaskExporter.listTargets(http, resolveBaseUrl(accessToken), accessToken);
    }

    // --- Cloud ID resolution ---

    private String resolveBaseUrl(String accessToken) {
        return buildBaseUrl(requireSite(accessToken).cloudId());
    }

    private JiraSite requireSite(String accessToken) {
        JiraSite site = resolveSite(accessToken);
        if (site == null) {
            throw new IllegalStateException("Could not resolve Jira Cloud ID. Is the Jira site accessible?");
        }
        return site;
    }

    private String buildBaseUrl(String cloudId) {
        return ATLASSIAN_API + "/ex/jira/" + cloudId + "/rest/api/3";
    }

    private JiraSite resolveSite(String accessToken) {
        return siteCache.computeIfAbsent(accessToken, token -> {
            try {
                HttpResponse<String> response = http.getRaw(
                        ATLASSIAN_API + "/oauth/token/accessible-resources", token, Duration.ofSeconds(10));
                if (response.statusCode() != 200) {
                    log.error("Failed to resolve Jira Cloud ID: {} {}", response.statusCode(), response.body());
                    return null;
                }

                JsonNode resources = objectMapper.readTree(response.body());
                if (resources.isArray() && !resources.isEmpty()) {
                    JsonNode first = resources.get(0);
                    return new JiraSite(first.get("id").asText(), first.hasNonNull("url") ? first.get("url").asText() : null);
                }
                log.warn("No accessible Jira resources found");
                return null;
            } catch (Exception e) {
                log.error("Failed to resolve Jira Cloud ID", e);
                return null;
            }
        });
    }
}
