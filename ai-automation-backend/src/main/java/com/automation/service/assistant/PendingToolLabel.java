package com.automation.service.assistant;

import java.util.Map;

/**
 * What a confirmation card needs to name a proposed action: the English fallback sentence
 * ({@code description}), the resolved human name ({@code subject}, may be null) and small
 * parameters such as {@code priority}/{@code status} ({@code params}, never null).
 * Built by {@link PendingToolDescriber}.
 */
public record PendingToolLabel(String description, String subject, Map<String, String> params) {

    public PendingToolLabel {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** Only a fallback sentence, no structured data. */
    public static PendingToolLabel of(String description) {
        return new PendingToolLabel(description, null, Map.of());
    }
}
