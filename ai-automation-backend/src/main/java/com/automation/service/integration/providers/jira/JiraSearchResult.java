package com.automation.service.integration.providers.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraSearchResult(
        List<JiraIssue> issues,
        int total,
        int maxResults,
        int startAt
) {}
