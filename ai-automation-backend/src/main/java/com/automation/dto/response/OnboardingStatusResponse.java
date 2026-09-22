package com.automation.dto.response;

import java.util.List;

public record OnboardingStatusResponse(
        boolean enabled,
        String format,
        List<String> profileIds,
        boolean fileExists,
        String filePath,
        /** Language of the written text. */
        String language,
        /** A file without the MySuricates marker is in the way — it is never overwritten. */
        boolean fileForeign
) {}
