package com.automation.service.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GitWebhookPayloadParserTest {

    private GitWebhookPayloadParser parser;

    @BeforeEach
    void setUp() {
        parser = new GitWebhookPayloadParser();
    }

    // --- Provider detection ---

    @Test
    void parse_nullPayload_returnsEmpty() {
        assertThat(parser.parse(null, new HttpHeaders())).isEmpty();
    }

    @Test
    void parse_emptyPayload_returnsEmpty() {
        assertThat(parser.parse(Map.of(), new HttpHeaders())).isEmpty();
    }

    @Test
    void parse_unknownProvider_returnsEmpty() {
        var headers = new HttpHeaders();
        var result = parser.parse(Map.of("key", "value"), headers);
        assertThat(result).isEmpty();
    }

    // --- GitHub push ---

    @Test
    void parseGitHub_push_extractsFields() {
        var headers = new HttpHeaders();
        headers.set("X-GitHub-Event", "push");

        Map<String, Object> payload = new HashMap<>();
        payload.put("ref", "refs/heads/main");
        payload.put("before", "aaa");
        payload.put("after", "bbb");
        payload.put("repository", Map.of("full_name", "user/repo"));
        payload.put("pusher", Map.of("name", "flo"));
        payload.put("commits", List.of(
                Map.of("message", "fix: something", "author", Map.of("name", "Flo"))
        ));

        var result = parser.parse(payload, headers);

        assertThat(result).containsEntry("git_provider", "github");
        assertThat(result).containsEntry("git_event", "push");
        assertThat(result).containsEntry("git_branch", "main");
        assertThat(result).containsEntry("git_repository", "user/repo");
        assertThat(result).containsEntry("git_before", "aaa");
        assertThat(result).containsEntry("git_after", "bbb");
        assertThat(result).containsEntry("git_commit_sha", "bbb");
        assertThat(result).containsEntry("git_commit_message", "fix: something");
        assertThat(result).containsEntry("git_author", "Flo");
        assertThat(result).containsEntry("git_pusher", "flo");
    }

    @Test
    void parseGitHub_tagPush_extractsTag() {
        var headers = new HttpHeaders();
        headers.set("X-GitHub-Event", "push");

        Map<String, Object> payload = new HashMap<>();
        payload.put("ref", "refs/tags/v1.0.0");
        payload.put("after", "ccc");
        payload.put("repository", Map.of("full_name", "user/repo"));

        var result = parser.parse(payload, headers);

        assertThat(result).containsEntry("git_tag", "v1.0.0");
        assertThat(result).doesNotContainKey("git_branch");
    }

    @Test
    void parseGitHub_push_emptyCommits_noAuthor() {
        var headers = new HttpHeaders();
        headers.set("X-GitHub-Event", "push");

        Map<String, Object> payload = new HashMap<>();
        payload.put("ref", "refs/heads/dev");
        payload.put("commits", List.of());

        var result = parser.parse(payload, headers);

        assertThat(result).containsEntry("git_branch", "dev");
        assertThat(result).doesNotContainKey("git_commit_message");
        assertThat(result).doesNotContainKey("git_author");
    }

    // --- GitHub pull_request ---

    @Test
    void parseGitHub_pullRequest_extractsFields() {
        var headers = new HttpHeaders();
        headers.set("X-GitHub-Event", "pull_request");

        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "opened");
        payload.put("repository", Map.of("full_name", "user/repo"));
        payload.put("sender", Map.of("login", "flo"));
        payload.put("pull_request", Map.of(
                "number", 42,
                "title", "Add feature",
                "head", Map.of("ref", "feature-branch", "sha", "abc123")
        ));

        var result = parser.parse(payload, headers);

        assertThat(result).containsEntry("git_event", "pull_request");
        assertThat(result).containsEntry("git_action", "opened");
        assertThat(result).containsEntry("git_pr_number", 42);
        assertThat(result).containsEntry("git_pr_title", "Add feature");
        assertThat(result).containsEntry("git_branch", "feature-branch");
        assertThat(result).containsEntry("git_commit_sha", "abc123");
        assertThat(result).containsEntry("git_author", "flo");
    }

    // --- GitHub create/delete ---

    @Test
    void parseGitHub_createBranch_extractsRef() {
        var headers = new HttpHeaders();
        headers.set("X-GitHub-Event", "create");

        Map<String, Object> payload = new HashMap<>();
        payload.put("ref", "new-branch");
        payload.put("ref_type", "branch");
        payload.put("repository", Map.of("full_name", "user/repo"));

        var result = parser.parse(payload, headers);

        assertThat(result).containsEntry("git_event", "create");
        assertThat(result).containsEntry("git_branch", "new-branch");
        assertThat(result).doesNotContainKey("git_tag");
    }

    @Test
    void parseGitHub_createTag_extractsTag() {
        var headers = new HttpHeaders();
        headers.set("X-GitHub-Event", "create");

        Map<String, Object> payload = new HashMap<>();
        payload.put("ref", "v2.0.0");
        payload.put("ref_type", "tag");
        payload.put("repository", Map.of("full_name", "user/repo"));

        var result = parser.parse(payload, headers);

        assertThat(result).containsEntry("git_tag", "v2.0.0");
    }

    // --- GitLab push ---

    @Test
    void parseGitLab_push_extractsFields() {
        var headers = new HttpHeaders();
        headers.set("X-Gitlab-Event", "Push Hook");

        Map<String, Object> payload = new HashMap<>();
        payload.put("object_kind", "push");
        payload.put("ref", "refs/heads/main");
        payload.put("before", "aaa");
        payload.put("after", "bbb");
        payload.put("project", Map.of("path_with_namespace", "group/repo"));
        payload.put("commits", List.of(
                Map.of("message", "feat: new thing", "author", Map.of("name", "Dev"))
        ));

        var result = parser.parse(payload, headers);

        assertThat(result).containsEntry("git_provider", "gitlab");
        assertThat(result).containsEntry("git_event", "push");
        assertThat(result).containsEntry("git_branch", "main");
        assertThat(result).containsEntry("git_repository", "group/repo");
        assertThat(result).containsEntry("git_commit_message", "feat: new thing");
    }

    // --- GitLab merge_request ---

    @Test
    void parseGitLab_mergeRequest_extractsFields() {
        var headers = new HttpHeaders();
        headers.set("X-Gitlab-Event", "Merge Request Hook");

        Map<String, Object> payload = new HashMap<>();
        payload.put("object_kind", "merge_request");
        payload.put("user_username", "dev");
        payload.put("project", Map.of("path_with_namespace", "group/repo"));
        payload.put("object_attributes", Map.of(
                "iid", 7,
                "title", "MR Title",
                "source_branch", "feature",
                "action", "open"
        ));

        var result = parser.parse(payload, headers);

        assertThat(result).containsEntry("git_event", "merge_request");
        assertThat(result).containsEntry("git_pr_number", 7);
        assertThat(result).containsEntry("git_pr_title", "MR Title");
        assertThat(result).containsEntry("git_branch", "feature");
        assertThat(result).containsEntry("git_author", "dev");
    }

    // --- Gitea push ---

    @Test
    void parseGitea_push_extractsFields() {
        var headers = new HttpHeaders();
        headers.set("X-Gitea-Event", "push");

        Map<String, Object> payload = new HashMap<>();
        payload.put("ref", "refs/heads/develop");
        payload.put("after", "ddd");
        payload.put("repository", Map.of("full_name", "org/project"));
        payload.put("pusher", Map.of("login", "admin"));
        payload.put("commits", List.of(
                Map.of("message", "chore: update deps", "author", Map.of("name", "Admin"))
        ));

        var result = parser.parse(payload, headers);

        assertThat(result).containsEntry("git_provider", "gitea");
        assertThat(result).containsEntry("git_branch", "develop");
        assertThat(result).containsEntry("git_repository", "org/project");
        assertThat(result).containsEntry("git_pusher", "admin");
    }

    @Test
    void parseGitea_tagPush_extractsTag() {
        var headers = new HttpHeaders();
        headers.set("X-Gitea-Event", "push");

        Map<String, Object> payload = new HashMap<>();
        payload.put("ref", "refs/tags/v3.0");
        payload.put("repository", Map.of("full_name", "org/project"));

        var result = parser.parse(payload, headers);

        assertThat(result).containsEntry("git_tag", "v3.0");
        assertThat(result).doesNotContainKey("git_branch");
    }

    // --- Gitea pull_request ---

    @Test
    void parseGitea_pullRequest_extractsFields() {
        var headers = new HttpHeaders();
        headers.set("X-Gitea-Event", "pull_request");

        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "opened");
        payload.put("repository", Map.of("full_name", "org/project"));
        payload.put("sender", Map.of("login", "user"));
        payload.put("pull_request", Map.of(
                "number", 5,
                "title", "Fix issue",
                "head", Map.of("label", "fix-branch", "sha", "eee")
        ));

        var result = parser.parse(payload, headers);

        assertThat(result).containsEntry("git_pr_number", 5);
        assertThat(result).containsEntry("git_branch", "fix-branch");
        assertThat(result).containsEntry("git_author", "user");
    }
}
