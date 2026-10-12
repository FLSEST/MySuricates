package com.automation.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
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
 * <p>The environment is only one path. The key files and the database in the user's home stay readable
 * for every process of the same user; outside attended runs {@link ProcessSandbox} hides them when
 * {@code automation.hardening.unattended-sandbox} is on (TODO 9525820a). Both go through
 * {@link #scrub(ProcessBuilder)}, so every spawn site that scrubs is sandboxed as well. AI CLIs and shell
 * blocks go through {@link #isolate} instead: with {@code automation.hardening.unattended-in-container}
 * they run in a container outside attended runs ({@link UnattendedContainer}, TODO 6ee57b87) and start
 * through {@link #start(ProcessBuilder)}, which removes that container again.
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
     * given, {@linkplain #isolate isolated}. Start it with {@link #start(ProcessBuilder)}.
     */
    public static ProcessBuilder forAgent(List<String> command, String workingDirectory) {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            pb.directory(new File(workingDirectory));
        }
        isolate(pb);
        return pb;
    }

    /**
     * For AI CLIs and shell blocks, after the working directory is set: strips the backend's secrets, then
     * turns the command into a container start when {@linkplain UnattendedContainer#active() that mode
     * applies} — the working directory and {@code writable} mounted under their own paths — or else adds the
     * {@linkplain RunAccess run token} of the run on this thread and puts the
     * {@linkplain ProcessSandbox process sandbox} in front of it when that is active.
     */
    public static void isolate(ProcessBuilder pb, Path... writable) {
        scrub(pb.environment());
        if (!UnattendedContainer.apply(pb, List.of(writable))) {
            // The run token reaches host processes only: in a container the backend on 127.0.0.1 is out of
            // reach anyway, and the variables set here would only go to the docker client (TODO cfb5d56b).
            RunAccess.addTo(pb.environment());
            ProcessSandbox.apply(pb);
        }
    }

    /** Starts a builder from {@link #isolate}; a container it starts is removed once the process has ended. */
    public static Process start(ProcessBuilder pb) throws IOException {
        return UnattendedContainer.start(pb);
    }

    /**
     * Removes the backend's own secrets from the environment a child process is started with, and puts
     * the {@linkplain ProcessSandbox process sandbox} in front of its command when that is active.
     */
    public static void scrub(ProcessBuilder pb) {
        scrub(pb.environment());
        ProcessSandbox.apply(pb);
    }

    static void scrub(Map<String, String> environment) {
        BACKEND_SECRETS.forEach(environment::remove);
        // A token inherited by the backend itself (started from inside a run) belongs to that other run
        environment.remove(RunAccess.TOKEN_ENV);
        environment.remove(RunAccess.API_ENV);
    }
}
