package com.automation.service.ai.status;

import java.time.Instant;
import java.util.List;

/**
 * Whether the AI CLIs on the machine the backend runs on are ready — for the start page and the
 * settings (f5a273ae), so a newcomer learns it before a run fails. Serialized directly.
 *
 * <p>Only names, commands and yes/no values leave it: never a token, never the content of a login
 * file. "Ready" means the program resolves on the PATH and a login was found; whether that login
 * is still valid only a real call would tell.
 *
 * @param container the backend runs in a container: the programs and logins that count are the ones
 *                  in there, so the plain commands below do not simply belong into the terminal of the
 *                  person reading this (the start path decides how a login gets in)
 * @param checkedAt when the machine was looked at — the answer is cached ({@link AiProviderStatusService})
 * @param providers one entry per registered provider, ordered by id
 */
public record AiProviderStatusReport(boolean container, Instant checkedAt, List<ProviderStatus> providers) {

    public AiProviderStatusReport {
        providers = List.copyOf(providers);
    }

    public enum State {
        /** Program on the PATH and a login found. */
        READY,
        /** Program on the PATH, no login found. */
        LOGIN_MISSING,
        /** Program not on the PATH — a login may still be there (e.g. an API key variable). */
        NOT_INSTALLED
    }

    public enum LoginVia { ENVIRONMENT, FILE }

    /**
     * @param id              registry id (CLAUDE, CODEX, PI)
     * @param cli             program name on the PATH
     * @param state           see {@link State}
     * @param installed       the program resolves on the PATH of the backend
     * @param loggedIn        a login was found
     * @param loginVia        where it was found; null without one
     * @param loginSource     the variable name, or the login file as {@code ~/…} or {@code $VARIABLE/…} —
     *                        never a value; null without a login
     * @param install         how to install the program
     * @param login           the command to log in — only the command, it is what gets copied
     * @param defaultProvider the provider a workflow uses when it names none
     * @param loginNote       what to do after the command, as a key the interface translates
     *                        ({@code ProviderSetup#loginNote}, eab43a8b); null when the command says it all
     */
    public record ProviderStatus(String id, String cli, State state, boolean installed, boolean loggedIn,
                                 LoginVia loginVia, String loginSource, String install, String login,
                                 boolean defaultProvider, String loginNote) {}
}
