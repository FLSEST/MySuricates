package com.automation.service.integration.providers.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraStatus(
        String id,
        String name,
        Map<String, Object> statusCategory
) {}
