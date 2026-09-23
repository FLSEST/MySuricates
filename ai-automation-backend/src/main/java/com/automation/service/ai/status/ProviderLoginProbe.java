package com.automation.service.ai.status;

import com.automation.service.ai.ProviderSetup;
import com.automation.service.ai.status.AiProviderStatusReport.LoginVia;
import com.automation.util.ClaudeOAuthToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The installer's login rule ({@code provider_logged_in} in scripts/lib/providers.sh) in Java: a login
 * counts when one of the provider's variables is set and not blank, or one of its login files is not
 * empty and — for {@code file:pattern} — has a line containing the pattern. The first hit wins.
 *
 * <p>Two differences, both towards what the CLIs of this instance actually see:
 * <ul>
 *   <li>{@code CLAUDE_CODE_OAUTH_TOKEN} must be plausible: a placeholder from a copied instruction is
 *       no login (9bef7e8c, {@link ClaudeOAuthToken}).</li>
 *   <li>A login file under {@link ProviderSetup#loginDir()} is looked up where
 *       {@link ProviderSetup#loginDirEnv()} points, if it is set — container instances move the CLIs'
 *       directories to /app/data/providers (scripts/lib/host-mounts.sh).</li>
 * </ul>
 *
 * <p>Nothing here logs, and a {@link Login} carries only a name: the variable's, or the file's as
 * {@code ~/…} or {@code $VARIABLE/…}. A file is read line by line only when it has a pattern, and
 * only until the pattern shows up.
 */
final class ProviderLoginProbe {

    /** Where a login was found — a variable name or a login file, never a value. */
    record Login(LoginVia via, String source) {}

    private ProviderLoginProbe() {
    }

    static Optional<Login> find(ProviderSetup setup, UnaryOperator<String> env, Path home) {
        for (String variable : setup.loginEnv()) {
            if (isSet(variable, env.apply(variable))) {
                return Optional.of(new Login(LoginVia.ENVIRONMENT, variable));
            }
        }
        for (String entry : setup.loginFiles()) {
            int colon = entry.indexOf(':');
            String file = colon < 0 ? entry : entry.substring(0, colon);
            String pattern = colon < 0 ? "" : entry.substring(colon + 1);
            Optional<Login> login = fileLogin(setup, file, pattern, env, home);
            if (login.isPresent()) {
                return login;
            }
        }
        return Optional.empty();
    }

    private static boolean isSet(String variable, String value) {
        if (ClaudeOAuthToken.VARIABLE.equals(variable)) {
            return ClaudeOAuthToken.isPlausible(value);
        }
        return value != null && !value.isBlank();
    }

    private static Optional<Login> fileLogin(ProviderSetup setup, String file, String pattern,
                                             UnaryOperator<String> env, Path home) {
        String dir = setup.loginDir();
        String variable = setup.loginDirEnv();
        try {
            if (dir != null && variable != null && file.startsWith(dir + "/")) {
                String moved = env.apply(variable);
                if (moved != null && !moved.isBlank()) {
                    String rest = file.substring(dir.length() + 1);
                    return holdsLogin(Path.of(moved).resolve(rest), pattern)
                            ? Optional.of(new Login(LoginVia.FILE, "$" + variable + "/" + rest))
                            : Optional.empty();
                }
            }
            return holdsLogin(home.resolve(file), pattern)
                    ? Optional.of(new Login(LoginVia.FILE, "~/" + file))
                    : Optional.empty();
        } catch (RuntimeException unusablePath) {
            // e.g. a variable holding a path this platform cannot represent: the CLI would not find it either
            return Optional.empty();
        }
    }

    /** Not empty, as bash {@code -s}; with a pattern, some line must contain it, as {@code grep -F}. */
    private static boolean holdsLogin(Path file, String pattern) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) == 0) {
                return false;
            }
            if (pattern.isEmpty()) {
                return true;
            }
            // InputStreamReader replaces malformed bytes instead of throwing like Files.newBufferedReader
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(pattern)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }
}
