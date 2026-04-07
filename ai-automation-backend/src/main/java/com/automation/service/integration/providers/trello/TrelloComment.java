package com.automation.service.integration.providers.trello;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TrelloComment(
        String id,
        String type,
        String date,
        TrelloCommentData data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TrelloCommentData(
            String text,
            TrelloCard card
    ) {}
}
