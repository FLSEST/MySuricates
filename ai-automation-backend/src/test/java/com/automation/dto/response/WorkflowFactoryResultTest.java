package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowFactoryResultTest {

    @Test
    void should_construct_with_all_fields() {
        List<String> retries = List.of("err1", "err2");
        WorkflowFactoryResult result = new WorkflowFactoryResult(
                "wf-123",
                "My Workflow",
                "SUCCESS",
                "test-run-456",
                "PASSED",
                null,
                true,
                1234L,
                retries
        );

        assertThat(result.workflowId()).isEqualTo("wf-123");
        assertThat(result.workflowName()).isEqualTo("My Workflow");
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.testRunId()).isEqualTo("test-run-456");
        assertThat(result.testStatus()).isEqualTo("PASSED");
        assertThat(result.testError()).isNull();
        assertThat(result.promotedAsTemplate()).isTrue();
        assertThat(result.durationMs()).isEqualTo(1234L);
        assertThat(result.retryErrors()).containsExactly("err1", "err2");
    }

    @Test
    void should_normalize_null_retryErrors_to_empty_list() {
        WorkflowFactoryResult result = new WorkflowFactoryResult(
                "wf-1", "name", "FAILED", null, null, "boom", false, 0L, null
        );

        assertThat(result.retryErrors()).isNotNull().isEmpty();
    }

    @Test
    void should_preserve_empty_retryErrors_list() {
        WorkflowFactoryResult result = new WorkflowFactoryResult(
                "wf-1", "name", "SUCCESS", "tr", "PASSED", null, true, 100L, List.of()
        );

        assertThat(result.retryErrors()).isEmpty();
    }

    @Test
    void should_allow_null_optional_string_fields() {
        WorkflowFactoryResult result = new WorkflowFactoryResult(
                "wf-1", "name", "SUCCESS", null, null, null, false, 0L, List.of()
        );

        assertThat(result.testRunId()).isNull();
        assertThat(result.testStatus()).isNull();
        assertThat(result.testError()).isNull();
    }

    @Test
    void should_set_promotedAsTemplate_to_false() {
        WorkflowFactoryResult result = new WorkflowFactoryResult(
                "wf-1", "name", "FAILED", null, null, "err", false, 50L, List.of()
        );

        assertThat(result.promotedAsTemplate()).isFalse();
    }

    @Test
    void should_handle_zero_duration() {
        WorkflowFactoryResult result = new WorkflowFactoryResult(
                "wf-1", "name", "SUCCESS", "tr", "PASSED", null, true, 0L, List.of()
        );

        assertThat(result.durationMs()).isZero();
    }

    @Test
    void should_handle_large_duration() {
        WorkflowFactoryResult result = new WorkflowFactoryResult(
                "wf-1", "name", "SUCCESS", "tr", "PASSED", null, true, Long.MAX_VALUE, List.of()
        );

        assertThat(result.durationMs()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void should_be_equal_for_same_values() {
        WorkflowFactoryResult a = new WorkflowFactoryResult(
                "wf-1", "name", "SUCCESS", "tr", "PASSED", null, true, 100L, List.of("e1")
        );
        WorkflowFactoryResult b = new WorkflowFactoryResult(
                "wf-1", "name", "SUCCESS", "tr", "PASSED", null, true, 100L, List.of("e1")
        );

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_not_be_equal_when_workflowId_differs() {
        WorkflowFactoryResult a = new WorkflowFactoryResult(
                "wf-1", "name", "SUCCESS", "tr", "PASSED", null, true, 100L, List.of()
        );
        WorkflowFactoryResult b = new WorkflowFactoryResult(
                "wf-2", "name", "SUCCESS", "tr", "PASSED", null, true, 100L, List.of()
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_not_be_equal_when_status_differs() {
        WorkflowFactoryResult a = new WorkflowFactoryResult(
                "wf-1", "name", "SUCCESS", "tr", "PASSED", null, true, 100L, List.of()
        );
        WorkflowFactoryResult b = new WorkflowFactoryResult(
                "wf-1", "name", "FAILED", "tr", "PASSED", null, true, 100L, List.of()
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_not_be_equal_when_promotedAsTemplate_differs() {
        WorkflowFactoryResult a = new WorkflowFactoryResult(
                "wf-1", "name", "SUCCESS", "tr", "PASSED", null, true, 100L, List.of()
        );
        WorkflowFactoryResult b = new WorkflowFactoryResult(
                "wf-1", "name", "SUCCESS", "tr", "PASSED", null, false, 100L, List.of()
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_not_be_equal_when_durationMs_differs() {
        WorkflowFactoryResult a = new WorkflowFactoryResult(
                "wf-1", "name", "SUCCESS", "tr", "PASSED", null, true, 100L, List.of()
        );
        WorkflowFactoryResult b = new WorkflowFactoryResult(
                "wf-1", "name", "SUCCESS", "tr", "PASSED", null, true, 200L, List.of()
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_not_be_equal_when_retryErrors_differ() {
        WorkflowFactoryResult a = new WorkflowFactoryResult(
                "wf-1", "name", "SUCCESS", "tr", "PASSED", null, true, 100L, List.of("e1")
        );
        WorkflowFactoryResult b = new WorkflowFactoryResult(
                "wf-1", "name", "SUCCESS", "tr", "PASSED", null, true, 100L, List.of("e2")
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_treat_null_and_empty_retryErrors_as_equal() {
        WorkflowFactoryResult fromNull = new WorkflowFactoryResult(
                "wf-1", "name", "SUCCESS", "tr", "PASSED", null, true, 100L, null
        );
        WorkflowFactoryResult fromEmpty = new WorkflowFactoryResult(
                "wf-1", "name", "SUCCESS", "tr", "PASSED", null, true, 100L, List.of()
        );

        assertThat(fromNull).isEqualTo(fromEmpty);
        assertThat(fromNull.hashCode()).isEqualTo(fromEmpty.hashCode());
    }

    @Test
    void should_include_field_values_in_toString() {
        WorkflowFactoryResult result = new WorkflowFactoryResult(
                "wf-xyz", "MyWorkflow", "SUCCESS", "tr-1", "PASSED", null, true, 999L, List.of("err")
        );

        String str = result.toString();
        assertThat(str).contains("wf-xyz");
        assertThat(str).contains("MyWorkflow");
        assertThat(str).contains("SUCCESS");
        assertThat(str).contains("999");
    }

    @Test
    void should_handle_multiple_retry_errors() {
        List<String> errors = List.of("timeout", "connection refused", "invalid response");
        WorkflowFactoryResult result = new WorkflowFactoryResult(
                "wf-1", "name", "SUCCESS", "tr", "PASSED", null, true, 100L, errors
        );

        assertThat(result.retryErrors()).hasSize(3)
                .containsExactly("timeout", "connection refused", "invalid response");
    }
}
