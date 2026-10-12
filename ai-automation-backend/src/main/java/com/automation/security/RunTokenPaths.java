package com.automation.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Arrays;
import java.util.List;

/**
 * The one list of what a run token may read (TODO cfb5d56b): GET only, the overview a workflow needs —
 * workflows, runs, TODOs, projects, skills, goals, audits, learning insights. {@code SecurityConfig}
 * builds its rule from it and {@link ApiKeyAuthFilter} refuses everything else itself, so both read the
 * same matchers.
 *
 * <p>Deliberately an allowlist, not "every GET": exports, versions, the live log and the open questions
 * of other runs, settings, integrations, webhooks and the heavy scans stay behind the API key. What is on
 * the list is readable across runs — outputs and inputs of other runs included — which the
 * documentation says plainly. An id segment matches hex digits and dashes only, so words like
 * {@code analyze}, {@code count} or {@code seal-status} never pass as an id.
 */
public final class RunTokenPaths {

    private static final String ID = "{id:[0-9a-fA-F-]+}";
    private static final String KEY = "{key:[a-z0-9._-]+}";

    /** Readable with a run token (GET). */
    public static final List<String> READABLE = List.of(
            "/api/v1/workflows",
            "/api/v1/workflows/" + ID,
            "/api/v1/workflows/" + ID + "/runs",
            "/api/v1/executions",
            "/api/v1/executions/" + ID,
            "/api/v1/todos",
            "/api/v1/todos/" + ID,
            "/api/v1/todos/count",
            "/api/v1/todos/overview",
            "/api/v1/projects",
            "/api/v1/projects/" + ID,
            "/api/v1/projects/" + ID + "/workflows",
            "/api/v1/skills",
            "/api/v1/skills/" + ID,
            "/api/v1/goals",
            "/api/v1/goals/" + ID,
            "/api/v1/goals/" + ID + "/work-packages",
            "/api/v1/goals/" + ID + "/milestones",
            "/api/v1/audits",
            "/api/v1/audits/" + KEY,
            "/api/v1/audits/" + KEY + "/reports",
            "/api/v1/audit-reports/" + ID,
            "/api/v1/audit-findings",
            "/api/v1/audit-findings/" + ID,
            "/api/v1/learning/dashboard",
            "/api/v1/learning/platform-stats",
            "/api/v1/learning/workflows/" + ID + "/stats",
            "/api/v1/learning/workflows/" + ID + "/suggestions",
            "/api/v1/learning/workflows/" + ID + "/execution-insights",
            "/api/v1/learning/runs/" + ID + "/insights");

    private static final RequestMatcher[] MATCHERS = READABLE.stream()
            .map(pattern -> PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, pattern))
            .toArray(RequestMatcher[]::new);

    private RunTokenPaths() {
    }

    /** GET matchers of {@link #READABLE}, for {@code SecurityConfig}. */
    public static RequestMatcher[] matchers() {
        return MATCHERS.clone();
    }

    /** Whether a run token may make this request. */
    public static boolean allows(HttpServletRequest request) {
        return Arrays.stream(MATCHERS).anyMatch(m -> m.matches(request));
    }
}
