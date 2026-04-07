package com.automation.service.integration.providers.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraUser(
        String accountId,
        String displayName,
        String emailAddress
) {}
