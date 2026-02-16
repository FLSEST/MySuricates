package com.automation.executor;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
@Builder
public class ExecutionContext {

    private final String containerId;
    private final String workflowRunId;

    @Setter
    private String previousOutput;

    @Builder.Default
    private final Map<String, Object> variables = new HashMap<>();

    @Builder.Default
    private final AtomicInteger stepCounter = new AtomicInteger(0);

    @Builder.Default
    private final AtomicBoolean paused = new AtomicBoolean(false);

    @Builder.Default
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public int nextStepIndex() {
        return stepCounter.getAndIncrement();
    }

    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }

    public Object getVariable(String name) {
        return variables.get(name);
    }

    public String resolveTemplate(String template) {
        if (template == null) return null;

        String resolved = template;
        resolved = resolved.replace("{{ previous_output }}", previousOutput != null ? previousOutput : "");
        resolved = resolved.replace("{{previous_output}}", previousOutput != null ? previousOutput : "");

        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            resolved = resolved.replace("{{ " + entry.getKey() + " }}", String.valueOf(entry.getValue()));
            resolved = resolved.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }

        return resolved;
    }

    public boolean shouldStop() {
        return cancelled.get();
    }

    public boolean isPaused() {
        return paused.get();
    }

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }

    public void cancel() {
        cancelled.set(true);
    }
}
