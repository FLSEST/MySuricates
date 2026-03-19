package com.automation.service.chain;

import java.util.List;
import java.util.Map;

public record BuiltInChainTemplate(
        String id,
        Map<String, String> name,
        Map<String, String> description,
        String icon,
        List<Entry> entries
) {
    public record Entry(
            String templateId,
            Map<String, String> label
    ) {}
}
