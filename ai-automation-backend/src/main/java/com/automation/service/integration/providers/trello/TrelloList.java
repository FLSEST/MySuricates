package com.automation.service.integration.providers.trello;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TrelloList(
        String id,
        String name,
        boolean closed,
        String idBoard,
        float pos
) {}
