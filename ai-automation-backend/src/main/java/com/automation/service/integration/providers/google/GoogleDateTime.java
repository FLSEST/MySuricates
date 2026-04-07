package com.automation.service.integration.providers.google;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleDateTime(
        String dateTime,
        String date,
        String timeZone
) {}
