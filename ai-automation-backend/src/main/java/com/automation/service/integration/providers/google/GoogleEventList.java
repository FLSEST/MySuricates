package com.automation.service.integration.providers.google;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleEventList(
        List<GoogleCalendarEvent> items,
        String nextPageToken,
        String summary
) {}
