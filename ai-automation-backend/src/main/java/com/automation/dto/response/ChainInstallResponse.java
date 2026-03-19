package com.automation.dto.response;

import java.util.List;

public record ChainInstallResponse(
        String chainId,
        String chainName,
        List<String> workflowIds
) {}
