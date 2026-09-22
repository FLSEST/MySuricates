package com.automation.entity;

/**
 * Who changed a skill. Determined by the calling code, never taken from a request body, and stored
 * in {@code skill_versions.change_source} as {@link #key()}. Counterpart of {@link ChangeSource}.
 */
public enum SkillChangeSource {
    /** A person through the REST API / UI. */
    USER("user"),
    /** {@code POST /api/v1/skills/import} — content that came from elsewhere. */
    IMPORT("import"),
    /** Built-in skills written from the bundled resources at startup (first seed or version bump). */
    BUILTIN_SEED("builtin-seed");

    private final String key;

    SkillChangeSource(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
