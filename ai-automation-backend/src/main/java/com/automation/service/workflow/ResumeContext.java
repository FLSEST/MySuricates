package com.automation.service.workflow;

import com.automation.entity.StepResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The context a resumed run continues with: its input context, every completed step's output as
 * {@code step_<i>_output} and the user variables those steps set ({@code contextDelta}), plus the last
 * output to seed {@code previous_output}. Built from the persisted steps in the resume transaction, then
 * handed detached to the run thread.
 */
record ResumeContext(Map<String, Object> variables, String previousOutput) {

    static ResumeContext rebuild(Map<String, Object> inputContext, List<StepResult> completedSteps) {
        Map<String, Object> reconstructed = new HashMap<>(inputContext != null ? inputContext : Map.of());
        String lastOutput = "";
        for (StepResult step : completedSteps) {
            String out = step.getOutput() != null ? step.getOutput() : "";
            reconstructed.put("step_" + step.getStepIndex() + "_output", out);
            lastOutput = out;
            if (step.getContextDelta() != null) {
                reconstructed.putAll(step.getContextDelta());
            }
        }
        return new ResumeContext(Map.copyOf(reconstructed), lastOutput);
    }
}
