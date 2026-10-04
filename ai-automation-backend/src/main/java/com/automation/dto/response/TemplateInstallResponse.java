package com.automation.dto.response;

import java.util.List;

/**
 * @param workflowId      the workflow created from the template
 * @param partWorkflowIds the workflows created for the blueprints its sub-workflow blocks call, in call order
 */
public record TemplateInstallResponse(
        String workflowId,
        String workflowName,
        List<String> partWorkflowIds
) {
}
