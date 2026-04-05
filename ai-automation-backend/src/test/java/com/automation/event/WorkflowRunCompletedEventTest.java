package com.automation.event;

import com.automation.entity.RunStatus;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRunCompletedEventTest {

    private static final Object SOURCE = new Object();

    // --- Constructor & Getters ---

    @Test
    void constructor_allFieldsProvided_storesAllValues() {
        Map<String, Object> runMeta = Map.of("key", "val");
        Map<String, Object> wfMeta = Map.of("autoTodo", Map.of("enabled", true));
        Map<String, Object> error = Map.of("message", "boom");

        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                SOURCE, "run-1", "wf-1", "My Workflow",
                "proj-1", "/path/to/project", RunStatus.COMPLETED,
                runMeta, wfMeta, error);

        assertThat(event.getRunId()).isEqualTo("run-1");
        assertThat(event.getWorkflowId()).isEqualTo("wf-1");
        assertThat(event.getWorkflowName()).isEqualTo("My Workflow");
        assertThat(event.getProjectId()).isEqualTo("proj-1");
        assertThat(event.getProjectPath()).isEqualTo("/path/to/project");
        assertThat(event.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(event.getRunMetadata()).containsEntry("key", "val");
        assertThat(event.getWorkflowMetadata()).containsKey("autoTodo");
        assertThat(event.getError()).containsEntry("message", "boom");
        assertThat(event.getSource()).isSameAs(SOURCE);
    }

    @Test
    void constructor_nullRunMetadata_returnsEmptyMap() {
        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                SOURCE, "run-1", "wf-1", "name",
                null, null, RunStatus.FAILED,
                null, Map.of(), Map.of());

        assertThat(event.getRunMetadata()).isNotNull().isEmpty();
    }

    @Test
    void constructor_nullWorkflowMetadata_returnsEmptyMap() {
        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                SOURCE, "run-1", "wf-1", "name",
                null, null, RunStatus.FAILED,
                Map.of(), null, Map.of());

        assertThat(event.getWorkflowMetadata()).isNotNull().isEmpty();
    }

    @Test
    void constructor_nullError_returnsEmptyMap() {
        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                SOURCE, "run-1", "wf-1", "name",
                null, null, RunStatus.FAILED,
                Map.of(), Map.of(), null);

        assertThat(event.getError()).isNotNull().isEmpty();
    }

    @Test
    void constructor_allNullableMapsNull_returnsEmptyMaps() {
        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                SOURCE, "run-1", "wf-1", "name",
                null, null, RunStatus.COMPLETED,
                null, null, null);

        assertThat(event.getRunMetadata()).isEmpty();
        assertThat(event.getWorkflowMetadata()).isEmpty();
        assertThat(event.getError()).isEmpty();
    }

    @Test
    void constructor_nullStringFields_preservesNulls() {
        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                SOURCE, null, null, null,
                null, null, RunStatus.PENDING,
                Map.of(), Map.of(), Map.of());

        assertThat(event.getRunId()).isNull();
        assertThat(event.getWorkflowId()).isNull();
        assertThat(event.getWorkflowName()).isNull();
        assertThat(event.getProjectId()).isNull();
        assertThat(event.getProjectPath()).isNull();
    }

    // --- isAutoTodoEnabled() ---

    @Test
    void isAutoTodoEnabled_enabledTrue_returnsTrue() {
        Map<String, Object> wfMeta = Map.of("autoTodo", Map.of("enabled", true));

        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                SOURCE, "run-1", "wf-1", "name",
                null, null, RunStatus.COMPLETED,
                Map.of(), wfMeta, Map.of());

        assertThat(event.isAutoTodoEnabled()).isTrue();
    }

    @Test
    void isAutoTodoEnabled_enabledFalse_returnsFalse() {
        Map<String, Object> wfMeta = Map.of("autoTodo", Map.of("enabled", false));

        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                SOURCE, "run-1", "wf-1", "name",
                null, null, RunStatus.COMPLETED,
                Map.of(), wfMeta, Map.of());

        assertThat(event.isAutoTodoEnabled()).isFalse();
    }

    @Test
    void isAutoTodoEnabled_noAutoTodoKey_returnsFalse() {
        Map<String, Object> wfMeta = Map.of("otherKey", "value");

        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                SOURCE, "run-1", "wf-1", "name",
                null, null, RunStatus.COMPLETED,
                Map.of(), wfMeta, Map.of());

        assertThat(event.isAutoTodoEnabled()).isFalse();
    }

    @Test
    void isAutoTodoEnabled_autoTodoNotAMap_returnsFalse() {
        Map<String, Object> wfMeta = Map.of("autoTodo", "not-a-map");

        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                SOURCE, "run-1", "wf-1", "name",
                null, null, RunStatus.COMPLETED,
                Map.of(), wfMeta, Map.of());

        assertThat(event.isAutoTodoEnabled()).isFalse();
    }

    @Test
    void isAutoTodoEnabled_autoTodoMapWithoutEnabledKey_returnsFalse() {
        Map<String, Object> wfMeta = Map.of("autoTodo", Map.of("mode", "track"));

        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                SOURCE, "run-1", "wf-1", "name",
                null, null, RunStatus.COMPLETED,
                Map.of(), wfMeta, Map.of());

        assertThat(event.isAutoTodoEnabled()).isFalse();
    }

    @Test
    void isAutoTodoEnabled_enabledIsNonBooleanValue_returnsFalse() {
        Map<String, Object> wfMeta = Map.of("autoTodo", Map.of("enabled", "yes"));

        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                SOURCE, "run-1", "wf-1", "name",
                null, null, RunStatus.COMPLETED,
                Map.of(), wfMeta, Map.of());

        assertThat(event.isAutoTodoEnabled()).isFalse();
    }

    @Test
    void isAutoTodoEnabled_emptyWorkflowMetadata_returnsFalse() {
        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                SOURCE, "run-1", "wf-1", "name",
                null, null, RunStatus.COMPLETED,
                Map.of(), Map.of(), Map.of());

        assertThat(event.isAutoTodoEnabled()).isFalse();
    }

    @Test
    void isAutoTodoEnabled_nullWorkflowMetadata_returnsFalse() {
        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                SOURCE, "run-1", "wf-1", "name",
                null, null, RunStatus.COMPLETED,
                Map.of(), null, Map.of());

        assertThat(event.isAutoTodoEnabled()).isFalse();
    }

    @Test
    void isAutoTodoEnabled_autoTodoIsBoolean_returnsFalse() {
        Map<String, Object> wfMeta = Map.of("autoTodo", true);

        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                SOURCE, "run-1", "wf-1", "name",
                null, null, RunStatus.COMPLETED,
                Map.of(), wfMeta, Map.of());

        assertThat(event.isAutoTodoEnabled()).isFalse();
    }

    @Test
    void isAutoTodoEnabled_enabledWithAdditionalFields_returnsTrue() {
        Map<String, Object> autoTodo = new HashMap<>();
        autoTodo.put("enabled", true);
        autoTodo.put("mode", "track");
        Map<String, Object> wfMeta = Map.of("autoTodo", autoTodo);

        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                SOURCE, "run-1", "wf-1", "name",
                null, null, RunStatus.COMPLETED,
                Map.of(), wfMeta, Map.of());

        assertThat(event.isAutoTodoEnabled()).isTrue();
    }

    // --- ApplicationEvent inheritance ---

    @Test
    void extendsApplicationEvent_timestampIsSet() {
        long before = System.currentTimeMillis();

        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                SOURCE, "run-1", "wf-1", "name",
                null, null, RunStatus.COMPLETED,
                Map.of(), Map.of(), Map.of());

        long after = System.currentTimeMillis();

        assertThat(event.getTimestamp()).isBetween(before, after);
    }
}
