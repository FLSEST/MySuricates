package com.automation.service.integration.providers.trello;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TrelloMember(
        String id,
        String fullName,
        String username,
        String avatarUrl
) {}
