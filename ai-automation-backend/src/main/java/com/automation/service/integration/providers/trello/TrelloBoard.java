package com.automation.service.integration.providers.trello;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TrelloBoard(
        String id,
        String name,
        String desc,
        String url,
        boolean closed,
        String idOrganization
) {}
