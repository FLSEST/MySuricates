package com.automation.service.integration.providers.trello;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TrelloSearchResult(
        List<TrelloCard> cards,
        List<TrelloBoard> boards
) {}
