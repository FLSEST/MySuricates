package com.automation.modules;

/** Where a module's switch lives: {@code automation.modules.*} or {@code automation.hardening.*}. */
public enum ModuleKind {
    FEATURE("automation.modules"),
    HARDENING("automation.hardening");

    private final String propertyPrefix;

    ModuleKind(String propertyPrefix) {
        this.propertyPrefix = propertyPrefix;
    }

    public String propertyPrefix() {
        return propertyPrefix;
    }
}
