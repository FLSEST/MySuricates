package com.automation.util;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Environment hygiene for processes that run workflow-controlled content on the host: AI CLIs
 * (Claude, Codex, Pi), shell blocks and the commands behind integrations.
 *
 * <p>A {@link ProcessBuilder} starts with a copy of the backend's own environment. When the
 * backend itself was started with its secrets as environment variables — every Docker setup
 * ({@code deploy/compose.yml}, {@code docker-compose.*.yml}) passes {@code AUTOMATION_API_KEY} —
 * each child would inherit them, and a prompt-injected agent in an unattended run could drive the
 * whole REST API with the instance key. Children also pass the variable on: a backend, test suite
 * or Compose stack started from inside a run would silently adopt the instance key
 * ({@code application.yml}: {@code api-key: ${AUTOMATION_API_KEY:}}).
 *
 * <p>Until TODO 62037bf2 the backend even injected the key on purpose into every Claude/Codex
 * process (H1 fix, {@code cd75a519}); nothing used it. Credentials the AI CLIs need for their own
 * provider ({@code CLAUDE_CODE_OAUTH_TOKEN}, {@code ANTHROPIC_API_KEY}, {@code OPENAI_API_KEY}, …)
 * are not touched.
 *
 * <p>This only closes the environment path. The key file in the user's home stays readable for
 * every process of the same user — see TODO 9525820a.
 */
public final class ChildProcessEnv {

    /** Secrets of the backend itself that no child process needs. */
    static final Set<String> BACKEND_SECRETS = Set.of(
            "AUTOMATION_API_KEY",
            "AUTOMATION_SSL_PASSWORD");

    private ChildProcessEnv() {
    }

    /**
     * A process builder for an AI CLI: stderr merged into stdout, working directory set when
     * given, backend secrets {@linkplain #scrub(ProcessBuilder) stripped}.
     */
    public static ProcessBuilder forAgent(List<String> command, String workingDirectory) {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            pb.directory(new File(workingDirectory));
        }
        scrub(pb);
        return pb;
    }

    /** Removes the backend's own secrets from the environment a child process is started with. */
    public static void scrub(ProcessBuilder pb) {
        scrub(pb.environment());
    }

    static void scrub(Map<String, String> environment) {
        BACKEND_SECRETS.forEach(environment::remove);
    }
}
