package com.automation.dto.request;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecuteWorkflowRequestTest {

    @Test
    void should_setContext_when_constructedWithValue() {
        var context = Map.<String, Object>of("projectId", "p-1", "dryRun", true);

        var request = new ExecuteWorkflowRequest(context);

        assertThat(request.context()).isEqualTo(context);
    }

    @Test
    void should_allowNullContext_when_constructedWithNull() {
        var request = new ExecuteWorkflowRequest(null);

        assertThat(request.context()).isNull();
    }

    @Test
    void should_beEqual_when_contextMatches() {
        var context = Map.<String, Object>of("projectId", "p-1");
        var a = new ExecuteWorkflowRequest(context);
        var b = new ExecuteWorkflowRequest(context);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_containContextInToString() {
        var request = new ExecuteWorkflowRequest(Map.of("mode", "test"));

        assertThat(request.toString()).contains("context={mode=test}");
    }
}
