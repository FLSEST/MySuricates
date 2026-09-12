package com.automation.service.ai.catalog;

import com.automation.config.ModelCatalogConfig;
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
 */
@Component
@RequiredArgsConstructor
public class ClaudeModelCatalogSource implements ModelCatalogSource {

    public static final String SOURCE = "static-aliases";

    private final ModelCatalogConfig config;

    @Override
    public String providerId() {
        return "CLAUDE";
    }

    @Override
    public ModelCatalog fetch() {
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
