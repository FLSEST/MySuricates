package com.automation.exception;

public class AgenticPhaseException extends WorkflowExecutionException {

    public enum Phase { REASON, ACT, APPROVAL }

    private final Phase phase;

    public AgenticPhaseException(Phase phase, String message) {
        super(message);
        this.phase = phase;
    }

    public AgenticPhaseException(Phase phase, String message, Throwable cause) {
        super(message, cause);
        this.phase = phase;
    }

    public Phase getPhase() {
        return phase;
    }
}
