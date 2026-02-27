package com.automation.executor.blocks;

import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgenticBlockConfigTest {

    private ExecutionContext buildContext() {
        return ExecutionContext.builder()
                .containerId("test-container")
                .workflowRunId("run-1")
                .build();
    }

    @Test
    void from_fullConfig_parsesAllFields() {
        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Fix the tests");
        config.put("success_criteria", "All tests pass");
        config.put("max_iterations", 5);
        config.put("iteration_timeout_seconds", 120);
        config.put("total_timeout_seconds", 600);
        config.put("max_total_tokens", 100000);
        config.put("max_repeated_actions", 4);
        config.put("require_approval", true);
        config.put("timeout_seconds", 60);

        AgenticBlockConfig cfg = AgenticBlockConfig.from(config, buildContext());

        assertThat(cfg.goal()).isEqualTo("Fix the tests");
        assertThat(cfg.successCriteria()).isEqualTo("All tests pass");
        assertThat(cfg.maxIterations()).isEqualTo(5);
        assertThat(cfg.iterationTimeoutSeconds()).isEqualTo(120);
        assertThat(cfg.totalTimeoutSeconds()).isEqualTo(600);
        assertThat(cfg.maxTotalTokens()).isEqualTo(100000);
        assertThat(cfg.maxRepeatedActions()).isEqualTo(4);
        assertThat(cfg.requireApproval()).isTrue();
        assertThat(cfg.claudeTimeout()).isEqualTo(60);
    }

    @Test
    void from_minimalConfig_usesDefaults() {
        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Do something");

        AgenticBlockConfig cfg = AgenticBlockConfig.from(config, buildContext());

        assertThat(cfg.goal()).isEqualTo("Do something");
        assertThat(cfg.successCriteria()).isEmpty();
        assertThat(cfg.maxIterations()).isEqualTo(10);
        assertThat(cfg.iterationTimeoutSeconds()).isEqualTo(300);
        assertThat(cfg.totalTimeoutSeconds()).isEqualTo(1800);
        assertThat(cfg.maxTotalTokens()).isEqualTo(200_000);
        assertThat(cfg.maxRepeatedActions()).isEqualTo(3);
        assertThat(cfg.requireApproval()).isFalse();
        assertThat(cfg.claudeTimeout()).isNull();
    }

    @Test
    void from_missingGoal_throws() {
        Map<String, Object> config = new HashMap<>();

        assertThatThrownBy(() -> AgenticBlockConfig.from(config, buildContext()))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("goal");
    }

    @Test
    void from_resolvesTemplateVariables() {
        ExecutionContext ctx = buildContext();
        ctx.setVariable("target", "unit tests");

        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Fix {{ target }}");
        config.put("success_criteria", "{{ target }} pass");

        AgenticBlockConfig cfg = AgenticBlockConfig.from(config, ctx);

        assertThat(cfg.goal()).isEqualTo("Fix unit tests");
        assertThat(cfg.successCriteria()).isEqualTo("unit tests pass");
    }
}
