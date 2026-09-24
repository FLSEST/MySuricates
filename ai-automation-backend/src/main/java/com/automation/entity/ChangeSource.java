package com.automation.entity;

/**
 * Who changed a workflow definition. Determined by the calling code, never taken from a request
 * body, and stored in {@code workflow_versions.change_source} as {@link #key()}.
 * Older rows may still carry {@code "ai"} or {@code "api"} from before this enum existed.
 */
public enum ChangeSource {
    /** A person through the REST API / UI. */
    USER("user"),
    ASSISTANT("assistant"),
    /** Workflow factory: generated workflows and LLM auto-fixes (including auto-repair). */
    FACTORY("factory"),
    WISH("wish"),
    DIALOG("dialog"),
    CONVERSION("conversion"),
    CHAIN_TEMPLATE("chain-template"),
    POOL_INSTALL("pool-install"),
    /** The bundled example project: its example workflows, created from the shipped templates listed in ExampleProjectService. */
    EXAMPLE_PROJECT("example-project"),
    /** The goal guard: a workflow from the shipped template goal-guard, created when a goal's guard is switched on. */
    GOAL_GUARD("goal-guard"),
    IMPORT("import"),
    BPMN_IMPORT("bpmn-import"),
    RESTORE("restore"),
    /** State found on the next tracked change that no version row describes (legacy data, bypass writers, raw DB edits). */
    UNTRACKED("untracked");

    private final String key;

    ChangeSource(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
