package com.automation.entity;

import java.util.Map;

/**
 * What started a workflow run. Recorded on every run (provenance) and the basis of the optional
 * unattended-run policy ({@code automation.hardening.require-sealed-for-unattended}).
 */
public enum TriggerSource {
    /** A person started the run (REST execute, UI). */
    MANUAL(false, false),
    /** A person retried a failed run or a dead-letter entry. */
    RETRY(false, false),
    /** A person resumed an interrupted run. */
    RESUME(false, false),
    /** A person asked to resolve a TODO with its linked workflow. */
    TODO_RESOLUTION(false, false),
    /** The assistant started the run after the user confirmed the tool call. */
    ASSISTANT(false, false),
    SCHEDULE(true, false),
    WEBHOOK(true, false),
    TODO_TRIGGER(true, false),
    COMPLETION_TRIGGER(true, false),
    AUTO_REPAIR(true, false),
    WISH(true, false),
    /** Attendedness comes from whatever started the chain. */
    CHAIN(false, true),
    /** Attendedness comes from the parent run. */
    SUB_WORKFLOW(false, true);

    /** Reserved context key carrying the parent's effective source into chains and sub-workflows. */
    public static final String PARENT_CONTEXT_KEY = "__parent_trigger_source";

    /**
     * Context key carrying the chain run into its entry runs — the placeholder {@code {{ chain_run_id }}}.
     * Set by the chain executor after the chain's own input, so it is never taken from the input.
     */
    public static final String CHAIN_RUN_CONTEXT_KEY = "chain_run_id";

    private final boolean unattended;
    private final boolean inheritsFromParent;

    TriggerSource(boolean unattended, boolean inheritsFromParent) {
        this.unattended = unattended;
        this.inheritsFromParent = inheritsFromParent;
    }

    /** True if no person is involved in starting this run (ignores inheritance — see {@link #inheritsFromParent()}). */
    public boolean unattended() {
        return unattended;
    }

    public boolean inheritsFromParent() {
        return inheritsFromParent;
    }

    /**
     * The source that decides whether this run is attended: the source itself, or — for chains and
     * sub-workflows — the parent's source carried in {@link #PARENT_CONTEXT_KEY}. Returns null when
     * that is missing or unreadable; callers must treat an unknown origin as unattended.
     */
    public TriggerSource effective(Map<String, ?> inputContext) {
        if (!inheritsFromParent) {
            return this;
        }
        TriggerSource parent = parse(inputContext != null ? inputContext.get(PARENT_CONTEXT_KEY) : null);
        return parent != null && !parent.inheritsFromParent ? parent : null;
    }

    /** Effective source of a running workflow, read from its context variables (see {@code trigger_source}). */
    public static TriggerSource effectiveOfRun(Map<String, ?> runVariables) {
        TriggerSource own = parse(runVariables.get("trigger_source"));
        return own != null ? own.effective(runVariables) : null;
    }

    /**
     * The chain run a new run belongs to, for its run row: {@link #CHAIN_RUN_CONTEXT_KEY} from the
     * context — only for {@link #CHAIN}, the one source the chain executor starts with. Any other run
     * may carry the key (a retry keeps its context, a sub-workflow inherits its parent's), but it was
     * not started by that chain run: {@code null}.
     */
    public static String chainRunIdOf(TriggerSource source, Map<String, ?> inputContext) {
        if (source != CHAIN || inputContext == null) {
            return null;
        }
        return inputContext.get(CHAIN_RUN_CONTEXT_KEY) instanceof String id && !id.isBlank() ? id : null;
    }

    /** The source of that name, or {@code null} for {@code null} and for a name that is no source. */
    public static TriggerSource parse(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
