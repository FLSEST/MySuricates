package com.automation.modules;

/** What an enabled module exposes. Drives the risk badge on the settings page. */
public enum ModuleRisk {
    /** Stores or uses third-party credentials (OAuth tokens, PATs). */
    CREDENTIALS,
    /** Opens inbound endpoints or sends data to external services. */
    NETWORK,
    /** Gives programmatic access to the host (shell, containers, other instances). */
    HOST_ACCESS
}
