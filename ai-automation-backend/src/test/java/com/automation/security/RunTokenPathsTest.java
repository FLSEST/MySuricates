package com.automation.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RunTokenPathsTest {

    private static final String ID = "0bf5eed4-cfd8-4c1c-861c-1d30b149c1dc";

    private static boolean allows(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return RunTokenPaths.allows(request);
    }

    @ParameterizedTest(name = "GET {0}")
    @DisplayName("the read-only overview a workflow needs is allowed")
    @CsvSource({
            "/api/v1/workflows", "/api/v1/workflows/" + ID, "/api/v1/workflows/" + ID + "/runs",
            "/api/v1/executions", "/api/v1/executions/" + ID,
            "/api/v1/todos", "/api/v1/todos/" + ID, "/api/v1/todos/count", "/api/v1/todos/overview",
            "/api/v1/projects", "/api/v1/projects/" + ID, "/api/v1/projects/" + ID + "/workflows",
            "/api/v1/skills", "/api/v1/skills/" + ID,
            "/api/v1/goals", "/api/v1/goals/" + ID, "/api/v1/goals/" + ID + "/milestones",
            "/api/v1/audits", "/api/v1/audits/java.design", "/api/v1/audits/java.design/reports",
            "/api/v1/audit-reports/" + ID, "/api/v1/audit-findings", "/api/v1/audit-findings/" + ID,
            "/api/v1/learning/dashboard", "/api/v1/learning/platform-stats",
            "/api/v1/learning/workflows/" + ID + "/stats", "/api/v1/learning/runs/" + ID + "/insights"})
    void readsTheOverview(String path) {
        assertThat(allows("GET", path)).isTrue();
    }

    @ParameterizedTest(name = "{0} {1}")
    @DisplayName("writes, exports, live data of other runs, settings and heavy scans are refused")
    @CsvSource({
            "POST, /api/v1/todos", "PATCH, /api/v1/todos/" + ID + "/status", "DELETE, /api/v1/workflows/" + ID,
            "PUT, /api/v1/workflows/" + ID, "POST, /api/v1/workflows/" + ID + "/execute",
            "GET, /api/v1/workflows/analyze", "GET, /api/v1/workflows/seal-status",
            "GET, /api/v1/workflows/export/" + ID, "GET, /api/v1/workflows/" + ID + "/export/bpmn",
            "GET, /api/v1/workflows/" + ID + "/versions",
            "GET, /api/v1/executions/" + ID + "/live-log", "GET, /api/v1/executions/pending-input",
            "GET, /api/v1/executions/" + ID + "/approval",
            "GET, /api/v1/projects/" + ID + "/export", "GET, /api/v1/projects/" + ID + "/onboarding/preview",
            "GET, /api/v1/skills/export/" + ID, "GET, /api/v1/todos/similar",
            "GET, /api/v1/system/settings", "GET, /api/v1/integrations", "GET, /api/v1/webhooks",
            "GET, /api/v1/learning/workflows/" + ID + "/tool-patterns", "GET, /actuator/metrics",
            "GET, /api/v1/workflows/" + ID + "/../../system/settings"})
    void refusesEverythingElse(String method, String path) {
        assertThat(allows(method, path)).isFalse();
    }
}
