package com.automation.service.integration.providers.google;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleCalendarEvent(
        String id,
        String summary,
        String description,
        String location,
        String status,
        GoogleDateTime start,
        GoogleDateTime end,
        List<GoogleAttendee> attendees,
        String htmlLink
) {}
