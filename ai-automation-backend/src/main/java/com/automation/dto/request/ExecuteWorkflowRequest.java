package com.automation.dto.request;

import java.util.Map;

public record ExecuteWorkflowRequest(
        Map<String, Object> context
) {}
