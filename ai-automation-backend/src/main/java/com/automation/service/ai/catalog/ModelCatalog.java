package com.automation.service.ai.catalog;

import java.time.Instant;
import java.util.List;

/**
 * Models one AI provider offers right now, as discovered on the host.
 *
 * <p>Serialized directly to the frontend. {@code available=false} means the probe failed
 * (CLI missing, timeout, unparsable output) — {@code reason} then says why and {@code models}
 * is empty. The model field in the UI stays free-text, so an unavailable catalog never blocks
 * the user; it only removes the suggestions.
 *
 * @param providerId  registry id of the provider (CLAUDE, PI, CODEX)
 * @param available   whether the probe produced a usable list
 * @param source      where the list came from: {@code static-aliases}, {@code pi-cli},
 *                    {@code codex-cache}, {@code codex-cli}
 * @param fetchedAt   when the list was produced (for codex-cache: the cache file's own timestamp)
 * @param reason      human-readable failure reason when {@code available=false}, else null
 * @param models      the options, in the order the source suggests
 */
public record ModelCatalog(
        String providerId,
        boolean available,
        String source,
        Instant fetchedAt,
        String reason,
        List<ModelOption> models
) {

    /**
     * One selectable model.
     *
     * @param value            what gets stored in {@code workflow.model} and passed to the CLI
     * @param label            display text
     * @param description      optional longer text from the source
     * @param contextWindow    optional context window in tokens
     * @param supportsThinking optional flag whether the model exposes a reasoning/thinking level
     */
    public record ModelOption(
            String value,
            String label,
            String description,
            Integer contextWindow,
            Boolean supportsThinking
    ) {
        public static ModelOption of(String value, String label) {
            return new ModelOption(value, label, null, null, null);
        }
    }

    public static ModelCatalog available(String providerId, String source, Instant fetchedAt, List<ModelOption> models) {
        return new ModelCatalog(providerId, true, source, fetchedAt, null, List.copyOf(models));
    }

    public static ModelCatalog unavailable(String providerId, String source, String reason) {
        return new ModelCatalog(providerId, false, source, Instant.now(), reason, List.of());
    }
}
