package com.automation.service.integration.providers.openmeteo;

import com.automation.service.integration.providers.IntegrationParams;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/** Validation and query building for the Open-Meteo actions — pure functions, no I/O. */
final class OpenMeteoParams {

    static final String DEFAULT_HOURLY = "temperature_2m,precipitation_probability,precipitation,weather_code,wind_speed_10m";
    static final String DEFAULT_DAILY = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,"
            + "precipitation_probability_max,sunrise,sunset";
    static final String DEFAULT_CURRENT = "temperature_2m,weather_code,wind_speed_10m";
    static final int DEFAULT_DAYS = 7;
    static final int MAX_DAYS = 16;

    private static final Pattern VARIABLE = Pattern.compile("^[a-z0-9_]{1,64}$");
    private static final Pattern TIMEZONE = Pattern.compile("^[A-Za-z0-9_+\\-/]{1,64}$");
    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2}$");
    private static final int MAX_VARIABLES = 40;
    private static final List<Map.Entry<String, Set<String>>> UNITS = List.of(
            Map.entry("temperature_unit", Set.of("celsius", "fahrenheit")),
            Map.entry("wind_speed_unit", Set.of("kmh", "ms", "mph", "kn")),
            Map.entry("precipitation_unit", Set.of("mm", "inch")));

    private OpenMeteoParams() {}

    /** Query string for {@code /v1/forecast}. Empty {@code hourly}/{@code daily}/{@code current} leave that block out. */
    static String forecastQuery(Map<String, Object> params) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("latitude", plain(coordinate(params, "latitude", 90)));
        q.put("longitude", plain(coordinate(params, "longitude", 180)));
        q.put("forecast_days", String.valueOf(days(params.get("days"))));
        putVariables(q, "hourly", params, DEFAULT_HOURLY);
        putVariables(q, "daily", params, DEFAULT_DAILY);
        putVariables(q, "current", params, DEFAULT_CURRENT);
        q.put("timezone", timezone(params.get("timezone")));
        for (Map.Entry<String, Set<String>> unit : UNITS) {
            Object value = params.get(unit.getKey());
            if (value == null || value.toString().isBlank()) continue;
            String v = value.toString().trim().toLowerCase(Locale.ROOT);
            if (!unit.getValue().contains(v)) {
                throw new IllegalArgumentException("Parameter '" + unit.getKey() + "' must be one of " + unit.getValue());
            }
            q.put(unit.getKey(), v);
        }
        return join(q);
    }

    /** Query string for the geocoding search. */
    static String geocodeQuery(Map<String, Object> params) {
        Object nameValue = params.get("name");
        String name = nameValue == null ? "" : nameValue.toString().trim();
        if (name.length() < 2 || name.length() > 100) {
            throw new IllegalArgumentException("Parameter 'name' must be 2-100 characters");
        }
        int count = intParam(params.get("count"), 10, "count");
        if (count < 1 || count > 100) throw new IllegalArgumentException("Parameter 'count' must be 1-100");
        Object langValue = params.get("language");
        String language = langValue == null || langValue.toString().isBlank()
                ? "de" : langValue.toString().trim().toLowerCase(Locale.ROOT);
        if (!LANGUAGE.matcher(language).matches()) {
            throw new IllegalArgumentException("Parameter 'language' must be a two-letter code, e.g. de or en");
        }
        Map<String, String> q = new LinkedHashMap<>();
        q.put("name", name);
        q.put("count", String.valueOf(count));
        q.put("language", language);
        q.put("format", "json");
        return join(q);
    }

    static double coordinate(Map<String, Object> params, String key, double bound) {
        Object value = params.get(key);
        if (value == null || value.toString().isBlank()) throw IntegrationParams.missing(key);
        double d;
        try {
            d = value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Parameter '" + key + "' must be a number: " + value);
        }
        if (Double.isNaN(d) || d < -bound || d > bound) {
            throw new IllegalArgumentException("Parameter '" + key + "' must be between -" + (int) bound + " and " + (int) bound);
        }
        return d;
    }

    static int days(Object value) {
        int days = intParam(value, DEFAULT_DAYS, "days");
        if (days < 1 || days > MAX_DAYS) throw new IllegalArgumentException("Parameter 'days' must be 1-" + MAX_DAYS);
        return days;
    }

    static String timezone(Object value) {
        if (value == null || value.toString().isBlank()) return "auto";
        String tz = value.toString().trim();
        if (!TIMEZONE.matcher(tz).matches()) throw new IllegalArgumentException("Invalid timezone: " + tz);
        return tz;
    }

    /** Validated variable list; {@code null} when the caller passed an empty value (block disabled). */
    static List<String> variables(Object value, String defaults, String key) {
        String raw = value == null ? defaults : (value instanceof List<?> l ? String.join(",", l.stream().map(String::valueOf).toList()) : value.toString());
        List<String> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            String v = part.trim();
            if (v.isEmpty()) continue;
            if (!VARIABLE.matcher(v).matches()) throw new IllegalArgumentException("Invalid " + key + " variable: " + v);
            result.add(v);
        }
        if (result.size() > MAX_VARIABLES) throw new IllegalArgumentException("Too many " + key + " variables (max " + MAX_VARIABLES + ")");
        return result.isEmpty() ? null : result;
    }

    private static void putVariables(Map<String, String> q, String key, Map<String, Object> params, String defaults) {
        List<String> vars = variables(params.get(key), defaults, key);
        if (vars != null) q.put(key, String.join(",", vars));
    }

    /**
     * Read like its text ({@link IntegrationParams}): 5.0 fails like "5.0" always did, and a whole
     * number beyond int fails instead of wrapping around ({@code 4294967297} used to become 1).
     */
    private static int intParam(Object value, int defaultValue, String key) {
        Integer parsed;
        try {
            parsed = IntegrationParams.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Parameter '" + key + "' must be a whole number: " + value);
        }
        return parsed != null ? parsed : defaultValue;
    }

    private static String plain(double d) {
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private static String join(Map<String, String> q) {
        StringJoiner joiner = new StringJoiner("&");
        q.forEach((k, v) -> joiner.add(k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8).replace("%2C", ",")));
        return joiner.toString();
    }
}
