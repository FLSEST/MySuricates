package com.automation.service.workflow;

import com.automation.executor.ExecutionContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Computes the context delta persisted per step: user-defined variables that are not derivable
 * from step outputs, system-injected keys or the run's input context.
 */
final class ContextDeltaCalculator {

    static final Set<String> SYSTEM_VAR_KEYS = Set.of("workflow_run_id", "workflow_id", "workflow_name", "project_id", "project_path",
            "model", "provider", "effort", "started_at", "run_timestamp",
            "previous_report", "open_todos",
            "workflow_version", "workflow_changed_since_last_run", "workflow_definition_hash", "trigger_source");

    private ContextDeltaCalculator() {}

    static Map<String, Object> compute(ExecutionContext context, Map<String, Object> inputContext) {
        Map<String, Object> delta = new HashMap<>(context.getVariables());
        // Remove auto-set step_N_output variables — derivable from StepResult.output
        delta.entrySet().removeIf(e -> e.getKey().matches("step_\\d+_output"));
        // Remove system-injected constants
        SYSTEM_VAR_KEYS.forEach(delta::remove);
        // Remove inputContext vars — already stored in WorkflowRun.inputContext
        if (inputContext != null) {
            inputContext.keySet().forEach(delta::remove);
        }
        // The run's taint travels with the delta (resume rebuilds its input context from these),
        // after the removals above because the reserved keys are input-context keys themselves.
        delta.putAll(context.exportTaint());
        return delta.isEmpty() ? null : delta;
    }
}
