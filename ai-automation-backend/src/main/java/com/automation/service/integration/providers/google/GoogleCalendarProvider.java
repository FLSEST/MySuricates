package com.automation.service.integration.providers.google;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.automation.service.integration.providers.IntegrationParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleCalendarProvider implements IntegrationProvider {

    private static final String BASE_URL = "https://www.googleapis.com/calendar/v3";

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String getProviderId() {
        return "google-calendar";
    }

    @Override
    public String getDisplayName() {
        return "Google Calendar";
    }

    @Override
    public String getIcon() {
        return "Calendar";
    }

    @Override
    public String getDescription() {
        return "Kalender-Events lesen, erstellen und verwalten";
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        return new OAuthConfig(
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                List.of(
                        "https://www.googleapis.com/auth/calendar.events",
                        "https://www.googleapis.com/auth/userinfo.email",
                        "https://www.googleapis.com/auth/userinfo.profile"
                ),
                "GOOGLE_CLIENT_ID",
                "GOOGLE_CLIENT_SECRET",
                Map.of("access_type", "offline", "prompt", "consent")
        ).withRevocationUrl(OAuthConfig.GOOGLE_REVOCATION_URL);
    }

    @Override
    public String fetchAccountInfo(String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.googleapis.com/oauth2/v2/userinfo"))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
        } catch (Exception e) {
            log.warn("Failed to fetch Google account info: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
        return List.of(
                new IntegrationAction("list_events", "Kalender-Events in einem Zeitraum abrufen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "calendarId", Map.of("type", "string", "default", "primary", "description", "Kalender-ID (Standard: primary)"),
                                        "timeMin", Map.of("type", "string", "description", "Start-Zeitpunkt (ISO 8601, z.B. 2026-04-07T00:00:00Z)"),
                                        "timeMax", Map.of("type", "string", "description", "End-Zeitpunkt (ISO 8601)"),
                                        "maxResults", Map.of("type", "integer", "default", 10, "description", "Maximale Anzahl Events")
                                )
                        )),
                new IntegrationAction("get_event", "Ein einzelnes Event abrufen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "calendarId", Map.of("type", "string", "default", "primary"),
                                        "eventId", Map.of("type", "string", "description", "Event-ID")
                                ),
                                "required", List.of("eventId")
                        )),
                new IntegrationAction("create_event", "Ein neues Kalender-Event erstellen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "calendarId", Map.of("type", "string", "default", "primary"),
                                        "summary", Map.of("type", "string", "description", "Titel des Events"),
                                        "description", Map.of("type", "string", "description", "Beschreibung"),
                                        "location", Map.of("type", "string", "description", "Ort"),
                                        "startDateTime", Map.of("type", "string", "description", "Start (ISO 8601)"),
                                        "endDateTime", Map.of("type", "string", "description", "Ende (ISO 8601)"),
                                        "timeZone", Map.of("type", "string", "default", "Europe/Berlin"),
                                        "attendees", Map.of("type", "array", "description", "Liste von E-Mail-Adressen")
                                ),
                                "required", List.of("summary", "startDateTime", "endDateTime")
                        )),
                new IntegrationAction("update_event", "Ein bestehendes Event aktualisieren",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "calendarId", Map.of("type", "string", "default", "primary"),
                                        "eventId", Map.of("type", "string", "description", "Event-ID"),
                                        "summary", Map.of("type", "string"),
                                        "description", Map.of("type", "string"),
                                        "location", Map.of("type", "string"),
                                        "startDateTime", Map.of("type", "string"),
                                        "endDateTime", Map.of("type", "string"),
                                        "timeZone", Map.of("type", "string", "default", "Europe/Berlin")
                                ),
                                "required", List.of("eventId")
                        )),
                new IntegrationAction("delete_event", "Ein Event löschen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "calendarId", Map.of("type", "string", "default", "primary"),
                                        "eventId", Map.of("type", "string", "description", "Event-ID")
                                ),
                                "required", List.of("eventId")
                        )),
                new IntegrationAction("get_free_busy", "Verfügbarkeit für Zeitraum prüfen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "timeMin", Map.of("type", "string", "description", "Start (ISO 8601)"),
                                        "timeMax", Map.of("type", "string", "description", "Ende (ISO 8601)"),
                                        "calendarIds", Map.of("type", "array", "description", "Kalender-IDs (Standard: [primary])")
                                ),
                                "required", List.of("timeMin", "timeMax")
                        ))
        );
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        return switch (action) {
            case "list_events" -> listEvents(params, accessToken);
            case "get_event" -> getEvent(params, accessToken);
            case "create_event" -> createEvent(params, accessToken);
            case "update_event" -> updateEvent(params, accessToken);
            case "delete_event" -> deleteEvent(params, accessToken);
            case "get_free_busy" -> getFreeBusy(params, accessToken);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private Object listEvents(Map<String, Object> params, String accessToken) {
        String calendarId = paramString(params, "calendarId", "primary");
        StringBuilder url = new StringBuilder(BASE_URL)
                .append("/calendars/").append(urlEncode(calendarId))
                .append("/events?singleEvents=true&orderBy=startTime");

        if (params.containsKey("timeMin")) url.append("&timeMin=").append(urlEncode(paramString(params, "timeMin", "")));
        if (params.containsKey("timeMax")) url.append("&timeMax=").append(urlEncode(paramString(params, "timeMax", "")));
        url.append("&maxResults=").append(IntegrationParams.intParam(params, "maxResults", 10));

        return httpGet(url.toString(), accessToken, GoogleEventList.class);
    }

    private Object getEvent(Map<String, Object> params, String accessToken) {
        String calendarId = paramString(params, "calendarId", "primary");
        String eventId = requireParam(params, "eventId");
        String url = BASE_URL + "/calendars/" + urlEncode(calendarId) + "/events/" + urlEncode(eventId);
        return httpGet(url, accessToken, GoogleCalendarEvent.class);
    }

    private Object createEvent(Map<String, Object> params, String accessToken) {
        String calendarId = paramString(params, "calendarId", "primary");
        String timeZone = paramString(params, "timeZone", "Europe/Berlin");

        Map<String, Object> event = new java.util.HashMap<>();
        event.put("summary", requireParam(params, "summary"));
        if (params.containsKey("description")) event.put("description", params.get("description"));
        if (params.containsKey("location")) event.put("location", params.get("location"));
        event.put("start", Map.of("dateTime", requireParam(params, "startDateTime"), "timeZone", timeZone));
        event.put("end", Map.of("dateTime", requireParam(params, "endDateTime"), "timeZone", timeZone));

        if (params.containsKey("attendees") && params.get("attendees") instanceof List<?> emails) {
            event.put("attendees", emails.stream()
                    .map(e -> Map.of("email", e.toString()))
                    .toList());
        }

        String url = BASE_URL + "/calendars/" + urlEncode(calendarId) + "/events";
        return httpPost(url, event, accessToken, GoogleCalendarEvent.class);
    }

    private Object updateEvent(Map<String, Object> params, String accessToken) {
        String calendarId = paramString(params, "calendarId", "primary");
        String eventId = requireParam(params, "eventId");
        String timeZone = paramString(params, "timeZone", "Europe/Berlin");

        Map<String, Object> event = new java.util.HashMap<>();
        if (params.containsKey("summary")) event.put("summary", params.get("summary"));
        if (params.containsKey("description")) event.put("description", params.get("description"));
        if (params.containsKey("location")) event.put("location", params.get("location"));
        if (params.containsKey("startDateTime"))
            event.put("start", Map.of("dateTime", params.get("startDateTime"), "timeZone", timeZone));
        if (params.containsKey("endDateTime"))
            event.put("end", Map.of("dateTime", params.get("endDateTime"), "timeZone", timeZone));

        String url = BASE_URL + "/calendars/" + urlEncode(calendarId) + "/events/" + urlEncode(eventId);
        return httpPut(url, event, accessToken, GoogleCalendarEvent.class);
    }

    private Object deleteEvent(Map<String, Object> params, String accessToken) {
        String calendarId = paramString(params, "calendarId", "primary");
        String eventId = requireParam(params, "eventId");
        String url = BASE_URL + "/calendars/" + urlEncode(calendarId) + "/events/" + urlEncode(eventId);
        httpDelete(url, accessToken);
        return Map.of("deleted", true, "eventId", eventId);
    }

    @SuppressWarnings("unchecked")
    private Object getFreeBusy(Map<String, Object> params, String accessToken) {
        List<String> calendarIds = params.containsKey("calendarIds") && params.get("calendarIds") instanceof List<?>
                ? ((List<?>) params.get("calendarIds")).stream().map(Object::toString).toList()
                : List.of("primary");

        Map<String, Object> body = Map.of(
                "timeMin", requireParam(params, "timeMin"),
                "timeMax", requireParam(params, "timeMax"),
                "items", calendarIds.stream().map(id -> Map.of("id", id)).toList()
        );

        return httpPost(BASE_URL + "/freeBusy", body, accessToken, Object.class);
    }

    // --- HTTP helpers ---

    private <T> T httpGet(String url, String accessToken, Class<T> responseType) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return objectMapper.readValue(response.body(), responseType);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Google Calendar API request failed: " + e.getMessage(), e);
        }
    }

    private <T> T httpPost(String url, Object body, String accessToken, Class<T> responseType) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return objectMapper.readValue(response.body(), responseType);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Google Calendar API request failed: " + e.getMessage(), e);
        }
    }

    private <T> T httpPut(String url, Object body, String accessToken, Class<T> responseType) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return objectMapper.readValue(response.body(), responseType);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Google Calendar API request failed: " + e.getMessage(), e);
        }
    }

    private void httpDelete(String url, String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .DELETE()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 204 && response.statusCode() != 200) {
                checkResponse(response);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Google Calendar API request failed: " + e.getMessage(), e);
        }
    }

    private void checkResponse(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Google Calendar API error " + response.statusCode() + ": " + response.body());
        }
    }

    // --- Parameter helpers ---

    private static String paramString(Map<String, Object> params, String key, String defaultValue) {
        Object val = params.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    private static String requireParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null) throw new IllegalArgumentException("Required parameter missing: " + key);
        return val.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
