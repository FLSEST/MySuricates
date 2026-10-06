package com.automation.service.audit;

import java.util.Map;

/**
  * The audit agreement of a workflow, read from its metadata: under which key the
  * audit runs, in which category, and which block writes the result envelope.
 *
  * <p>Moved out of {@code AuditIngestionService} (TODO e39379a9) — a concept of its own, a file of its
  * own; the service was otherwise at the ratchet limit.
 *
  * @param promptBlock   the AI block whose step names the report's provider and model; without a value the
  *                      {@code reportBlock}. Set explicitly when a shell block assembles the envelope
  *                      (test coverage, TODO fa66ea31)
  * @param schemaVersion currently only {@code 1}; an unknown version makes the configuration
  *                      invalid instead of half-understanding it
 */
record AuditConfig(String key, String category, String reportBlock, String promptBlock, int schemaVersion) {

    /** @return {@code null} if the workflow carries no enabled, complete audit block */
    static AuditConfig from(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get("audit");
        if (!(value instanceof Map<?, ?> map) || !Boolean.TRUE.equals(map.get("enabled"))) return null;
        String key = text(map.get("key"));
        String category = text(map.get("category"));
        String reportBlock = text(map.get("reportBlock"));
        int version = map.get("schemaVersion") instanceof Number n ? n.intValue() : 1;
        if (key == null || category == null || reportBlock == null || version != 1) return null;
        String promptBlock = text(map.get("promptBlock"));
        return new AuditConfig(key, category, reportBlock, promptBlock != null ? promptBlock : reportBlock, version);
    }

    private static String text(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
