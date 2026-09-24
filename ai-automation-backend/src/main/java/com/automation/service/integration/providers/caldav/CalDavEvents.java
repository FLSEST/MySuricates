package com.automation.service.integration.providers.caldav;

import com.automation.service.integration.providers.caldav.CalDavHttpClient.DavResult;
import com.automation.service.integration.providers.caldav.CalDavXml.DavResponse;
import com.automation.service.integration.providers.ical.ICalDateTime;
import com.automation.service.integration.providers.ical.ICalEvent;
import com.automation.service.integration.providers.ical.ICalParser;
import com.automation.service.integration.providers.ical.ICalPatcher;
import com.automation.service.integration.providers.ical.ICalWriter;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.automation.service.integration.providers.ical.CalendarParams.*;

/**
 * The event actions of the CalDAV provider. Listing uses a calendar-query REPORT with server-side
 * {@code <C:expand>}, so series arrive as single instances; writing uses conditional PUTs (new
 * objects with {@code If-None-Match: *}, updates with {@code If-Match}) and changes an existing
 * object in place via {@link ICalPatcher}, keeping properties this provider does not know.
 */
final class CalDavEvents {

    static final String DEFAULT_TIME_ZONE = "Europe/Berlin";
    private static final String SORT_KEY = "_sortKey";
    private static final DateTimeFormatter CALDAV_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final CalDavHttpClient http;
    private final CalDavDiscovery discovery;

    CalDavEvents(CalDavHttpClient http, CalDavDiscovery discovery) {
        this.http = http;
        this.discovery = discovery;
    }

    Map<String, Object> list(CalDavCredentials cred, Map<String, Object> params) {
        ZoneId zone = zone(params, "timeZone", ZoneId.of(DEFAULT_TIME_ZONE));
        Instant from = instant(params, "from", LocalDate.now(zone).atStartOfDay(zone).toInstant(), false, zone);
        Instant to = instant(params, "to", from.plus(Duration.ofDays(30)), true, zone);
        if (!to.isAfter(from)) throw new IllegalArgumentException("'to' must be after 'from'");
        int limit = Math.max(1, Math.min(paramInt(params, "limit", 200), 2000));

        String calendar = paramString(params, "calendar", null);
        List<URI> calendars = new ArrayList<>();
        if (calendar != null) {
            calendars.add(cred.resolveCollection(calendar));
        } else {
            for (Map<String, Object> c : discovery.calendars(cred)) calendars.add(URI.create(c.get("url").toString()));
        }

        List<Map<String, Object>> events = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String query = CalDavXml.calendarQuery(CALDAV_UTC.format(from), CALDAV_UTC.format(to));
        for (URI url : calendars) {
            try {
                events.addAll(query(cred, url, query, zone));
            } catch (IllegalStateException e) {
                if (calendar != null) throw e;
                errors.add(url.getRawPath() + ": " + e.getMessage()); // one unreadable calendar must not hide the others
            }
        }
        events.sort(Comparator.comparing(e -> (Instant) e.get(SORT_KEY)));
        events.forEach(e -> e.remove(SORT_KEY));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("count", Math.min(events.size(), limit));
        result.put("truncated", events.size() > limit);
        result.put("events", events.size() > limit ? events.subList(0, limit) : events);
        if (!errors.isEmpty()) result.put("errors", errors);
        return result;
    }

    private List<Map<String, Object>> query(CalDavCredentials cred, URI calendar, String query, ZoneId zone) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DavResponse r : CalDavXml.parseMultistatus(http.report(cred, calendar, 1, query).body())) {
            String data = r.rawText(CalDavXml.CALDAV, "calendar-data");
            if (data == null) continue;
            URI resource;
            try {
                resource = cred.resolve(r.href());
            } catch (IllegalArgumentException e) {
                continue; // an href on another host is never followed, not even as data
            }
            String href = resource.getRawPath();
            for (ICalEvent event : ICalParser.parseEvents(data)) {
                Map<String, Object> map = event.toMap();
                map.put("href", href);
                String etag = r.text(CalDavXml.DAV, "getetag");
                if (etag != null) map.put("etag", etag);
                map.put("calendar", calendar.getRawPath());
                map.put(SORT_KEY, event.start() != null ? event.start().toInstant(zone) : Instant.MAX);
                out.add(map);
            }
        }
        return out;
    }

    Map<String, Object> create(CalDavCredentials cred, Map<String, Object> params) {
        URI calendar = cred.resolveCollection(requireParam(params, "calendar"));
        String timeZone = paramString(params, "timeZone", DEFAULT_TIME_ZONE);
        ICalDateTime start = ICalDateTime.fromIso(requireParam(params, "start"), timeZone);
        ICalDateTime end = endOrDefault(start, paramString(params, "end", null), timeZone);

        String id = UUID.randomUUID().toString();
        String uid = id + "@mysuricates";
        ICalEvent event = new ICalEvent(uid, requireParam(params, "summary"),
                paramString(params, "description", null), paramString(params, "location", null),
                null, start, end, paramString(params, "rrule", null), null);
        URI resource = calendar.resolve(id + ".ics");
        DavResult result = http.put(cred, resource, ICalWriter.calendar(event, Instant.now()), null, true);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", true);
        out.put("uid", uid);
        out.put("href", resource.getRawPath());
        if (result.etag() != null) out.put("etag", result.etag());
        out.put("calendar", calendar.getRawPath());
        return out;
    }

    Map<String, Object> update(CalDavCredentials cred, Map<String, Object> params) {
        URI resource = cred.resolve(requireParam(params, "href"));
        String timeZone = paramString(params, "timeZone", DEFAULT_TIME_ZONE);

        Set<String> remove = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();
        List<ICalDateTime> dates = new ArrayList<>();
        text(params, "summary", "SUMMARY", remove, lines);
        text(params, "description", "DESCRIPTION", remove, lines);
        text(params, "location", "LOCATION", remove, lines);
        date(params, "start", "DTSTART", timeZone, remove, lines, dates);
        date(params, "end", "DTEND", timeZone, remove, lines, dates);
        if (params.containsKey("rrule")) {
            remove.add("RRULE");
            String rrule = params.get("rrule") == null ? "" : params.get("rrule").toString();
            if (!rrule.isBlank()) lines.add("RRULE:" + ICalWriter.normalizeRrule(rrule));
        }
        if (remove.isEmpty()) {
            throw new IllegalArgumentException("Nothing to update — pass summary, description, location, start, end or rrule");
        }

        DavResult current = http.get(cred, resource);
        String ifMatch = paramString(params, "etag", current.etag());
        String patched = ICalPatcher.patch(current.body(), remove, lines,
                ICalWriter.zonesOf(dates.toArray(ICalDateTime[]::new)), Instant.now());
        DavResult result = http.put(cred, resource, patched, ifMatch, false);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", true);
        out.put("href", resource.getRawPath());
        if (result.etag() != null) out.put("etag", result.etag());
        out.put("changed", List.copyOf(remove));
        return out;
    }

    Map<String, Object> delete(CalDavCredentials cred, Map<String, Object> params) {
        URI resource = cred.resolve(requireParam(params, "href"));
        http.delete(cred, resource, paramString(params, "etag", null));
        return Map.of("deleted", true, "href", resource.getRawPath());
    }

    /** Missing end: one day after an all-day start, one hour after a timed one. Kinds must match. */
    static ICalDateTime endOrDefault(ICalDateTime start, String end, String timeZone) {
        if (end == null) {
            return start.allDay() ? ICalDateTime.ofDate(start.date().plusDays(1))
                    : new ICalDateTime(null, start.local().plusHours(1), start.zone(), start.tzid());
        }
        ICalDateTime parsed = ICalDateTime.fromIso(end, timeZone);
        if (parsed.allDay() != start.allDay()) {
            throw new IllegalArgumentException("'start' and 'end' must both be dates (all-day) or both date-times");
        }
        return parsed;
    }

    private static void text(Map<String, Object> params, String key, String property, Set<String> remove, List<String> lines) {
        if (!params.containsKey(key)) return;
        remove.add(property);
        Object value = params.get(key);
        if (value != null && !value.toString().isEmpty()) lines.add(ICalWriter.textLine(property, value.toString()));
    }

    private static void date(Map<String, Object> params, String key, String property, String timeZone,
                             Set<String> remove, List<String> lines, List<ICalDateTime> dates) {
        String value = paramString(params, key, null);
        if (value == null) return;
        ICalDateTime parsed = ICalDateTime.fromIso(value, timeZone);
        remove.add(property);
        lines.add(ICalWriter.dateLine(property, parsed));
        dates.add(parsed);
    }
}
