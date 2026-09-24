package com.automation.service.integration.providers.caldav;

import com.automation.service.integration.providers.caldav.CalDavXml.DavResponse;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service discovery as RFC 6764 §6 / RFC 4791 §6.2 describe it: the base URL names the
 * {@code current-user-principal}, the principal names its {@code calendar-home-set}, and a Depth-1
 * PROPFIND on the home lists the calendar collections. Servers that skip a step (older Radicale
 * setups, a URL that already is the home) fall back to the previous URL.
 */
final class CalDavDiscovery {

    private final CalDavHttpClient http;

    CalDavDiscovery(CalDavHttpClient http) {
        this.http = http;
    }

    record Principal(URI url, String displayName) {}

    Principal principal(CalDavCredentials cred) {
        List<DavResponse> responses = CalDavXml.parseMultistatus(
                http.propfind(cred, cred.baseUrl(), 0, CalDavXml.PROPFIND_PRINCIPAL).body());
        if (responses.isEmpty()) return new Principal(cred.baseUrl(), null);
        DavResponse first = responses.get(0);
        String principalHref = first.hrefIn(CalDavXml.DAV, "current-user-principal");
        URI url = principalHref != null ? cred.resolveCollection(principalHref) : cred.baseUrl();
        return new Principal(url, first.text(CalDavXml.DAV, "displayname"));
    }

    URI calendarHome(CalDavCredentials cred, URI principal) {
        List<DavResponse> responses = CalDavXml.parseMultistatus(
                http.propfind(cred, principal, 0, CalDavXml.PROPFIND_HOME).body());
        for (DavResponse r : responses) {
            String home = r.hrefIn(CalDavXml.CALDAV, "calendar-home-set");
            if (home != null) return cred.resolveCollection(home);
        }
        return principal;
    }

    /** Calendar collections that can hold events, in server order. */
    List<Map<String, Object>> calendars(CalDavCredentials cred) {
        URI home = calendarHome(cred, principal(cred).url());
        List<DavResponse> responses = CalDavXml.parseMultistatus(
                http.propfind(cred, home, 1, CalDavXml.PROPFIND_CALENDARS).body());
        List<Map<String, Object>> calendars = new ArrayList<>();
        for (DavResponse r : responses) {
            if (!r.hasChild(CalDavXml.DAV, "resourcetype", CalDavXml.CALDAV, "calendar")) continue;
            List<String> components = r.components();
            if (!components.isEmpty() && !components.contains("VEVENT")) continue;
            URI url = sameServer(cred, r.href());
            if (url == null) continue;
            Map<String, Object> calendar = new LinkedHashMap<>();
            String name = r.text(CalDavXml.DAV, "displayname");
            calendar.put("name", name != null ? name : lastSegment(url));
            calendar.put("href", url.getRawPath());
            calendar.put("url", url.toString());
            putIfPresent(calendar, "description", r.text(CalDavXml.CALDAV, "calendar-description"));
            putIfPresent(calendar, "color", r.text(CalDavXml.APPLE, "calendar-color"));
            putIfPresent(calendar, "ctag", r.text(CalDavXml.CALSERVER, "getctag"));
            if (!components.isEmpty()) calendar.put("components", components);
            calendars.add(calendar);
        }
        return calendars;
    }

    /** Collection URL of an href, or {@code null} (skipped) when a server lists a calendar on another host. */
    static URI sameServer(CalDavCredentials cred, String href) {
        try {
            return cred.resolveCollection(href);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String lastSegment(URI url) {
        String path = url.getPath();
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null) map.put(key, value);
    }
}
