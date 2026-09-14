package com.automation.entity;

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
}
