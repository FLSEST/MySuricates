package com.automation.service.workflow;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One platform variable — or a group of them, like the goal variables — that a run gets injected
 * (TODO 441a4c46). {@link WorkflowContextDataService} collects every bean of this type in
 * {@link org.springframework.core.annotation.Order} and gives each one its own error path: a source
 * that throws costs no other one, and its variables state the gap instead of going missing. A new
 * variable is a new bean; the collector does not grow with it.
 */
public interface PlatformVariableSource {

    /**
     * What a source may know about the run.
     *
     * @param workflowMeta the workflow's metadata ({@code metadata.audit} …), empty for a run without it
     * @param variables    the run's context variables so far ({@code goal_id} …), read only
     */
    record Request(String workflowId, String projectId, Map<String, Object> workflowMeta,
                   Function<String, Object> variables) {

        public Object variable(String name) {
            return variables.apply(name);
        }
    }

    /** The variables this source sets for the run; empty when it does not apply — then none is set. Must not throw. */
    List<String> names(Request request);

    /**
     * Their values by name, never {@code null} — a value that cannot be had is stated, not left out.
     * May throw: every name of {@link #names} then states the gap.
     */
    Map<String, String> load(Request request);
}
