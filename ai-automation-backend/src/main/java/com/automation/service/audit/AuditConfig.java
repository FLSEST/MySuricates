package com.automation.service.audit;

import java.util.Map;

/**
 * Die Audit-Abmachung eines Workflows, gelesen aus seinen Metadaten: unter welchem Schlüssel der
 * Audit läuft, in welcher Kategorie, und welcher Block den Ergebnis-Envelope schreibt.
 *
 * <p>Umzug aus {@code AuditIngestionService} (TODO e39379a9) — ein eigenes Konzept, eine eigene
 * Datei; der Service stand sonst am Ratchet an.
 *
 * @param schemaVersion aktuell nur {@code 1}; eine unbekannte Version macht die Konfiguration
 *                      ungültig, statt sie halb zu verstehen
 */
record AuditConfig(String key, String category, String reportBlock, int schemaVersion) {

    /** @return {@code null}, wenn der Workflow keinen aktivierten, vollständigen Audit-Block trägt */
    static AuditConfig from(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get("audit");
        if (!(value instanceof Map<?, ?> map) || !Boolean.TRUE.equals(map.get("enabled"))) return null;
        String key = text(map.get("key"));
        String category = text(map.get("category"));
        String reportBlock = text(map.get("reportBlock"));
        int version = map.get("schemaVersion") instanceof Number n ? n.intValue() : 1;
        if (key == null || category == null || reportBlock == null || version != 1) return null;
        return new AuditConfig(key, category, reportBlock, version);
    }

    private static String text(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
