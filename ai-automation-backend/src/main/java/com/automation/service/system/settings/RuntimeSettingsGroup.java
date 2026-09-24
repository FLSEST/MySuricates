package com.automation.service.system.settings;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Ein Themenblock laufzeit-aenderbarer Einstellungen. {@code ExecutionSettingsService} sammelt alle
 * Gruppen (Reihenfolge ueber {@code @Order}) und behaelt nur Persistieren, Validieren und
 * Zusammenfuehren — eine neue Einstellung aendert ihre Gruppe, nicht den Service.
 *
 * <p>Schluessel und Grenzen stehen weiter zentral in {@code ExecutionSettingKeys}; der Service
 * prueft jeden Wert, bevor eine Gruppe ihn sieht.
 */
public interface RuntimeSettingsGroup {

    /** Kanonischer Schluessel → Setter, in der Reihenfolge, in der DB-Werte beim Start greifen. */
    Map<String, Consumer<String>> appliers();

    /** Traegt die aktuellen Werte (camelCase-Schluessel, wie die Einstellungsseite sie liest) ein. */
    void currentValues(Map<String, Object> out);

    /**
     * Darf dieser (bereits validierte) Wert gesetzt werden? Laeuft fuer einen ganzen Batch, bevor
     * irgendein Wert angewendet wird; wirft bei Verbot.
     */
    default void guard(String key, String value) {
    }
}
