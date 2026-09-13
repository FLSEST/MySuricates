package com.automation.service.integration.providers.jira;

import com.automation.service.integration.TaskExportCapable.TaskExportInput;
import com.automation.service.integration.TaskExportCapable.TaskRef;
import com.automation.service.integration.TaskExportCapable.TaskTarget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.automation.service.integration.providers.jira.JiraParams.buildAdfText;

/**
 * Single-TODO export into Jira (the {@code TaskExportCapable} part of {@link JiraProvider}),
 * kept out of the provider so the provider stays under the source-size ratchet.
 *
 * <p>{@code target} is the Jira project key. Issues are created as type "Task"; labels are
 * sanitized because Jira rejects whitespace in labels.
 */
final class JiraTaskExporter {

    private JiraTaskExporter() {
    }

    /** Fail-fast target validation — runs before any network call. */
    static String requireProjectKey(String target) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("Jira export needs a project key as target");
        }
        return target.trim();
    }

    static TaskRef createTask(JiraHttpClient http, String baseUrl, String siteUrl, String projectKey,
                              TaskExportInput input, String accessToken) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("project", Map.of("key", projectKey));
        fields.put("summary", input.title() != null && !input.title().isBlank() ? input.title() : "TODO");
        fields.put("issuetype", Map.of("name", "Task"));
        if (input.body() != null && !input.body().isBlank()) {
            fields.put("description", buildAdfText(input.body()));
        }
        if (input.labels() != null && !input.labels().isEmpty()) {
            List<String> labels = input.labels().stream()
                    .filter(l -> l != null && !l.isBlank())
                    .map(l -> l.trim().replaceAll("\\s+", "-"))
                    .toList();
            if (!labels.isEmpty()) fields.put("labels", labels);
        }

        JiraIssue issue = http.post(baseUrl + "/issue", Map.of("fields", fields), accessToken, JiraIssue.class);
        if (issue == null || issue.key() == null) {
            throw new IllegalStateException("Jira issue creation returned no key");
        }
        String url = siteUrl != null ? siteUrl + "/browse/" + issue.key() : issue.self();
        return new TaskRef("jira", issue.key(), url);
    }

    static List<TaskTarget> listTargets(JiraHttpClient http, String baseUrl, String accessToken) {
        Object result = http.get(baseUrl + "/project/search?maxResults=100", accessToken, Object.class);
        List<TaskTarget> out = new ArrayList<>();
        if (result instanceof Map<?, ?> page && page.get("values") instanceof List<?> values) {
            for (Object o : values) {
                if (o instanceof Map<?, ?> project && project.get("key") != null) {
                    String key = project.get("key").toString();
                    Object name = project.get("name");
                    out.add(new TaskTarget(key, name != null ? key + " – " + name : key));
                }
            }
        }
        return out;
    }
}
