package com.automation.service.integration.providers.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraProject(
        String id,
        String key,
        String name,
        String projectTypeKey
) {}
