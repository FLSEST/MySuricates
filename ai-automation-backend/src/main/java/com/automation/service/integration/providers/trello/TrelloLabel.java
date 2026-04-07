package com.automation.service.integration.providers.trello;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TrelloLabel(
        String id,
        String name,
        String color
) {}
