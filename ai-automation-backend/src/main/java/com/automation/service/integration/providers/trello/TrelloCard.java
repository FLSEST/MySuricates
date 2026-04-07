package com.automation.service.integration.providers.trello;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TrelloCard(
        String id,
        String name,
        String desc,
        String url,
        String shortUrl,
        boolean closed,
        String idBoard,
        String idList,
        String due,
        boolean dueComplete,
        List<String> idMembers,
        List<TrelloLabel> labels,
        float pos
) {}
