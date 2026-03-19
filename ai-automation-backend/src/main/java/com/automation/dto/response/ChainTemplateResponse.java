package com.automation.dto.response;

import java.util.List;
import java.util.Map;

public record ChainTemplateResponse(
        String id,
        Map<String, String> name,
        Map<String, String> description,
        String icon,
        List<EntryInfo> entries
) {
    public record EntryInfo(
            String templateId,
            Map<String, String> label
    ) {}
}
