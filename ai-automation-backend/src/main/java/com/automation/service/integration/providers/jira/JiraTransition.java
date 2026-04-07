package com.automation.service.integration.providers.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraTransition(
        String id,
        String name,
        Map<String, Object> to
) {}
