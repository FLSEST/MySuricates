package com.automation.service.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses Git webhook payloads from GitHub, GitLab, and Gitea.
 * Extracts standardized fields prefixed with {@code git_} and adds them to the workflow context.
 * <p>
 * Provider is detected from HTTP headers:
 * <ul>
 *   <li>GitHub: {@code X-GitHub-Event}</li>
 *   <li>GitLab: {@code X-Gitlab-Event}</li>
 *   <li>Gitea:  {@code X-Gitea-Event}</li>
 * </ul>
 * <p>
 * All extracted fields use the {@code git_} prefix to avoid collisions with user-defined context vars.
 */
@Component
@Slf4j
public class GitWebhookPayloadParser {

    /**
     * Detects provider and extracts standardized git fields.
     *
     * @param payload raw JSON payload from the webhook request body
     * @param headers HTTP request headers from the webhook call
     * @return map of {@code git_*} fields to add to the workflow context (empty if not a recognized git provider)
     */
    public Map<String, Object> parse(Map<String, Object> payload, HttpHeaders headers) {
        if (payload == null || payload.isEmpty()) return Map.of();

        String githubEvent = headers.getFirst("X-GitHub-Event");
        String gitlabEvent = headers.getFirst("X-Gitlab-Event");
        String giteaEvent = headers.getFirst("X-Gitea-Event");

        if (githubEvent != null) return parseGitHub(payload, githubEvent);
        if (gitlabEvent != null) return parseGitLab(payload, gitlabEvent);
        if (giteaEvent != null) return parseGitea(payload, giteaEvent);

        return Map.of();
    }

    // ----- GitHub -----

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseGitHub(Map<String, Object> p, String event) {
        Map<String, Object> result = new HashMap<>();
        result.put("git_provider", "github");
        result.put("git_event", event);

        // Repository
        Map<String, Object> repo = (Map<String, Object>) p.get("repository");
        if (repo != null) {
            putIfNotNull(result, "git_repository", repo.get("full_name"));
        }

        switch (event) {
            case "push" -> {
                String ref = (String) p.get("ref");
                if (ref != null) result.put("git_branch", ref.replaceFirst("^refs/heads/", ""));
                putIfNotNull(result, "git_before", p.get("before"));
                putIfNotNull(result, "git_after", p.get("after"));
                putIfNotNull(result, "git_commit_sha", p.get("after"));

                List<?> commits = (List<?>) p.get("commits");
                if (commits != null && !commits.isEmpty()) {
                    Map<String, Object> head = (Map<String, Object>) commits.get(commits.size() - 1);
                    putIfNotNull(result, "git_commit_message", head.get("message"));
                    Map<String, Object> author = (Map<String, Object>) head.get("author");
                    if (author != null) putIfNotNull(result, "git_author", author.get("name"));
                }
                Map<String, Object> pusher = (Map<String, Object>) p.get("pusher");
                if (pusher != null) putIfNotNull(result, "git_pusher", pusher.get("name"));

                // Tag push: refs/tags/...
                String rawRef = (String) p.get("ref");
                if (rawRef != null && rawRef.startsWith("refs/tags/")) {
                    result.put("git_tag", rawRef.replaceFirst("^refs/tags/", ""));
                    result.remove("git_branch");
                }
            }
            case "pull_request" -> {
                Map<String, Object> pr = (Map<String, Object>) p.get("pull_request");
                putIfNotNull(result, "git_action", p.get("action"));
                if (pr != null) {
                    putIfNotNull(result, "git_pr_number", pr.get("number"));
                    putIfNotNull(result, "git_pr_title", pr.get("title"));
                    Map<String, Object> head = (Map<String, Object>) pr.get("head");
                    if (head != null) {
                        putIfNotNull(result, "git_branch", head.get("ref"));
                        putIfNotNull(result, "git_commit_sha", head.get("sha"));
                    }
                    Map<String, Object> sender = (Map<String, Object>) p.get("sender");
                    if (sender != null) putIfNotNull(result, "git_author", sender.get("login"));
                }
            }
            case "create", "delete" -> {
                putIfNotNull(result, "git_branch", p.get("ref"));
                putIfNotNull(result, "git_tag", "tag".equals(p.get("ref_type")) ? p.get("ref") : null);
            }
            default -> { /* event type logged only, no extra fields */ }
        }

        log.debug("Parsed GitHub {} event: repo={} branch={}", event,
                result.get("git_repository"), result.get("git_branch"));
        return result;
    }

    // ----- GitLab -----

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseGitLab(Map<String, Object> p, String eventHeader) {
        Map<String, Object> result = new HashMap<>();
        result.put("git_provider", "gitlab");

        String eventKind = (String) p.getOrDefault("object_kind", eventHeader.toLowerCase());
        result.put("git_event", eventKind);

        // Repository / project
        Map<String, Object> project = (Map<String, Object>) p.get("project");
        if (project != null) {
            putIfNotNull(result, "git_repository", project.get("path_with_namespace"));
        }

        switch (eventKind) {
            case "push", "tag_push" -> {
                String ref = (String) p.get("ref");
                if (ref != null) {
                    if (ref.startsWith("refs/tags/")) {
                        result.put("git_tag", ref.replaceFirst("^refs/tags/", ""));
                    } else {
                        result.put("git_branch", ref.replaceFirst("^refs/heads/", ""));
                    }
                }
                putIfNotNull(result, "git_before", p.get("before"));
                putIfNotNull(result, "git_after", p.get("after"));
                putIfNotNull(result, "git_commit_sha", p.get("after"));

                List<?> commits = (List<?>) p.get("commits");
                if (commits != null && !commits.isEmpty()) {
                    Map<String, Object> head = (Map<String, Object>) commits.get(commits.size() - 1);
                    putIfNotNull(result, "git_commit_message", head.get("message"));
                    Map<String, Object> author = (Map<String, Object>) head.get("author");
                    if (author != null) putIfNotNull(result, "git_author", author.get("name"));
                }
                Map<String, Object> userInfo = (Map<String, Object>) p.get("user_name");
                if (userInfo == null) putIfNotNull(result, "git_pusher", p.get("user_name"));
            }
            case "merge_request" -> {
                Map<String, Object> attrs = (Map<String, Object>) p.get("object_attributes");
                if (attrs != null) {
                    putIfNotNull(result, "git_pr_number", attrs.get("iid"));
                    putIfNotNull(result, "git_pr_title", attrs.get("title"));
                    putIfNotNull(result, "git_branch", attrs.get("source_branch"));
                    putIfNotNull(result, "git_commit_sha", attrs.get("last_commit"));
                    putIfNotNull(result, "git_action", attrs.get("action"));
                }
                putIfNotNull(result, "git_author", p.get("user_username"));
            }
            default -> { /* pass */ }
        }

        log.debug("Parsed GitLab {} event: repo={} branch={}", eventKind,
                result.get("git_repository"), result.get("git_branch"));
        return result;
    }

    // ----- Gitea -----

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseGitea(Map<String, Object> p, String event) {
        Map<String, Object> result = new HashMap<>();
        result.put("git_provider", "gitea");
        result.put("git_event", event);

        Map<String, Object> repo = (Map<String, Object>) p.get("repository");
        if (repo != null) {
            putIfNotNull(result, "git_repository", repo.get("full_name"));
        }

        switch (event) {
            case "push" -> {
                String ref = (String) p.get("ref");
                if (ref != null) {
                    if (ref.startsWith("refs/tags/")) {
                        result.put("git_tag", ref.replaceFirst("^refs/tags/", ""));
                    } else {
                        result.put("git_branch", ref.replaceFirst("^refs/heads/", ""));
                    }
                }
                putIfNotNull(result, "git_before", p.get("before"));
                putIfNotNull(result, "git_after", p.get("after"));
                putIfNotNull(result, "git_commit_sha", p.get("after"));

                List<?> commits = (List<?>) p.get("commits");
                if (commits != null && !commits.isEmpty()) {
                    Map<String, Object> head = (Map<String, Object>) commits.get(commits.size() - 1);
                    putIfNotNull(result, "git_commit_message", head.get("message"));
                    Map<String, Object> author = (Map<String, Object>) head.get("author");
                    if (author != null) putIfNotNull(result, "git_author", author.get("name"));
                }
                Map<String, Object> pusher = (Map<String, Object>) p.get("pusher");
                if (pusher != null) putIfNotNull(result, "git_pusher", pusher.get("login"));
            }
            case "pull_request" -> {
                Map<String, Object> pr = (Map<String, Object>) p.get("pull_request");
                putIfNotNull(result, "git_action", p.get("action"));
                if (pr != null) {
                    putIfNotNull(result, "git_pr_number", pr.get("number"));
                    putIfNotNull(result, "git_pr_title", pr.get("title"));
                    Map<String, Object> head = (Map<String, Object>) pr.get("head");
                    if (head != null) {
                        putIfNotNull(result, "git_branch", head.get("label"));
                        putIfNotNull(result, "git_commit_sha", head.get("sha"));
                    }
                    Map<String, Object> sender = (Map<String, Object>) p.get("sender");
                    if (sender != null) putIfNotNull(result, "git_author", sender.get("login"));
                }
            }
            default -> { /* pass */ }
        }

        log.debug("Parsed Gitea {} event: repo={} branch={}", event,
                result.get("git_repository"), result.get("git_branch"));
        return result;
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            map.put(key, value);
        }
    }
}
