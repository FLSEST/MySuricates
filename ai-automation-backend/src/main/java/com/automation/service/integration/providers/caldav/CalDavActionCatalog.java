package com.automation.service.integration.providers.caldav;

import com.automation.service.integration.IntegrationAction;

import java.util.List;
import java.util.Map;

/** Static catalog of the CalDAV actions (name, description, input schema). */
final class CalDavActionCatalog {

    private CalDavActionCatalog() {}

    private static final Map<String, Object> CALENDAR = Map.of("type", "string",
            "description", "Kalender-href oder -URL aus list_calendars (z.B. /remote.php/dav/calendars/flo/personal/)");
    private static final Map<String, Object> HREF = Map.of("type", "string",
            "description", "href des Termins aus list_events bzw. create_event");
    private static final Map<String, Object> ETAG = Map.of("type", "string",
            "description", "ETag aus list_events — Änderung schlägt fehl (412), wenn der Termin inzwischen geändert wurde");
    private static final Map<String, Object> TIME_ZONE = Map.of("type", "string", "default", CalDavEvents.DEFAULT_TIME_ZONE,
            "description", "Zeitzone für Zeitangaben ohne Offset");

    static List<IntegrationAction> actions() {
        return List.of(
                new IntegrationAction("list_calendars", "Kalender des Kontos auflisten",
                        Map.of("type", "object", "properties", Map.of())),
                new IntegrationAction("list_events",
                        "Termine in einem Zeitraum abrufen — Serien liefert der Server als Einzeltermine",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "calendar", Map.of("type", "string",
                                                "description", "Kalender-href (leer = alle Kalender)"),
                                        "from", Map.of("type", "string", "description", "Beginn (ISO 8601 oder Datum; Standard: heute)"),
                                        "to", Map.of("type", "string", "description", "Ende (ISO 8601 oder Datum inkl.; Standard: from + 30 Tage)"),
                                        "limit", Map.of("type", "integer", "default", 200, "description", "Max. Termine (bis 2000)"),
                                        "timeZone", Map.of("type", "string", "default", CalDavEvents.DEFAULT_TIME_ZONE,
                                                "description", "Zeitzone für Datumsangaben in from/to")
                                )
                        )),
                new IntegrationAction("create_event", "Einen neuen Termin anlegen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "calendar", CALENDAR,
                                        "summary", Map.of("type", "string", "description", "Titel"),
                                        "start", Map.of("type", "string", "description",
                                                "Beginn: 2026-10-01 (ganztägig), 2026-10-01T10:00:00 oder 2026-10-01T08:00:00Z"),
                                        "end", Map.of("type", "string", "description",
                                                "Ende (Standard: +1 Stunde bzw. +1 Tag bei ganztägig; ganztägig exklusiv)"),
                                        "timeZone", TIME_ZONE,
                                        "description", Map.of("type", "string", "description", "Beschreibung"),
                                        "location", Map.of("type", "string", "description", "Ort"),
                                        "rrule", Map.of("type", "string", "description", "Wiederholung, z.B. FREQ=WEEKLY;BYDAY=MO;COUNT=10")
                                ),
                                "required", List.of("calendar", "summary", "start")
                        )),
                new IntegrationAction("update_event",
                        "Einen Termin ändern — nur übergebene Felder, alles andere bleibt erhalten",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "href", HREF,
                                        "etag", ETAG,
                                        "summary", Map.of("type", "string", "description", "Neuer Titel"),
                                        "start", Map.of("type", "string", "description", "Neuer Beginn"),
                                        "end", Map.of("type", "string", "description", "Neues Ende"),
                                        "timeZone", TIME_ZONE,
                                        "description", Map.of("type", "string", "description", "Neue Beschreibung (leer = entfernen)"),
                                        "location", Map.of("type", "string", "description", "Neuer Ort (leer = entfernen)"),
                                        "rrule", Map.of("type", "string", "description", "Neue Wiederholung (leer = entfernen)")
                                ),
                                "required", List.of("href")
                        )),
                new IntegrationAction("delete_event", "Einen Termin löschen (bei Serien: die ganze Serie)",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("href", HREF, "etag", ETAG),
                                "required", List.of("href")
                        ))
        );
    }
}
