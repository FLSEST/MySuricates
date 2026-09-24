package com.automation.service.integration.providers.ical;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One VEVENT as far as MySuricates reads it. {@code rrule} is passed through verbatim — series are
 * never expanded here (CalDAV servers expand via {@code <C:expand>}); {@code recurrenceId} marks a
 * single instance or an overridden occurrence of a series.
 */
public record ICalEvent(
        String uid,
        String summary,
        String description,
        String location,
        String status,
        ICalDateTime start,
        ICalDateTime end,
        String rrule,
        String recurrenceId
) {

    /** A series master: has a rule and is not itself an instance. */
    public boolean recurring() {
        return rrule != null && recurrenceId == null;
    }

    /** JSON form; absent fields are left out instead of written as {@code null}. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "uid", uid);
        put(map, "summary", summary);
        put(map, "description", description);
        put(map, "location", location);
        put(map, "status", status);
        if (start != null) map.put("start", start.toMap());
        if (end != null) map.put("end", end.toMap());
        map.put("allDay", start != null && start.allDay());
        put(map, "rrule", rrule);
        put(map, "recurrenceId", recurrenceId);
        return map;
    }

    private static void put(Map<String, Object> map, String key, String value) {
        if (value != null) map.put(key, value);
    }
}
