package com.automation.dto.request;

import java.util.List;

public record UpdateChainRequest(
        String name,
        String description,
        String icon,
        List<ChainEntryRequest> entries,
        Boolean stopOnFailure
) {}
