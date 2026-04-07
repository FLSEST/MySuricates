package com.automation.service.integration.providers.google;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleAttendee(
        String email,
        String responseStatus,
        String displayName
) {}
