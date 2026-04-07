package com.automation.service.integration.providers.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraIssue(
        String id,
        String key,
        String self,
        Map<String, Object> fields
) {}
