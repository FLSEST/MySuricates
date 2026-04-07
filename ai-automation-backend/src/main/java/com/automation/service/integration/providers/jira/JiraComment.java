package com.automation.service.integration.providers.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraComment(
        String id,
        Map<String, Object> body,
        Map<String, Object> author,
        String created,
        String updated
) {}
