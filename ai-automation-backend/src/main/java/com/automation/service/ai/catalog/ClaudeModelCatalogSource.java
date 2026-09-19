package com.automation.service.ai.catalog;

import com.automation.config.ModelCatalogConfig;
import com.automation.util.HostProcessUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Claude Code ships no model listing command and no local catalog file, so the catalog is the
 * set of CLI aliases ({@code fable}, {@code opus}, {@code sonnet}, {@code haiku}). Each alias
 * resolves to the newest model of its family inside the CLI, which keeps this list current
 * without maintenance. Users can still pin a concrete id (e.g. {@code claude-opus-5}) via the
 * free-text model field.
 *
 * <p>The aliases only mean something with an installed CLI, so the source does what its siblings
 * do — it asks the host — only far more cheaply: the other sources run their CLI, this one merely
 * checks that {@value #CLI_COMMAND} resolves on the PATH (no process, no file read). Without it,
 * the catalog is {@link ModelCatalog#unavailable} with a reason, which is also the one honest
 * answer to "is an AI connected on this host?" that costs nothing — {@code ModelCatalogService}'s
 * TTL cache keeps even a page that asks on every render off the host.
 */
@Component
@RequiredArgsConstructor
public class ClaudeModelCatalogSource implements ModelCatalogSource {

    public static final String SOURCE = "static-aliases";

    /** The program {@code HostClaudeService} starts ({@code claude -p}), as it writes it in its argv. */
    public static final String CLI_COMMAND = "claude";

    private final ModelCatalogConfig config;
    private final HostProcessUtils hostProcessUtils;

    @Override
    public String providerId() {
        return "CLAUDE";
    }

    @Override
    public ModelCatalog fetch() {
        if (!hostProcessUtils.isOnPath(CLI_COMMAND)) {
            return ModelCatalog.unavailable(providerId(), SOURCE,
                    CLI_COMMAND + " is not installed on this host (not found on PATH)");
        }
        List<ModelCatalog.ModelOption> options = config.getClaudeAliases().stream()
                .map(alias -> new ModelCatalog.ModelOption(
                        alias,
                        alias + " (latest " + capitalize(alias) + ")",
                        "Alias — always the newest " + capitalize(alias) + " model of the installed Claude CLI",
                        null,
                        null))
                .toList();
        return ModelCatalog.available(providerId(), SOURCE, Instant.now(), options);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
