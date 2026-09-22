package com.automation.dto.response;

import com.automation.entity.WorkflowSealEvent;

import java.time.Instant;

/**
 * One entry of the seal history. {@code chainIntact} is false when this entry's hash or its
 * predecessor no longer fits — the history was changed outside the application.
 */
public record WorkflowSealEventResponse(String id, String workflowId, String workflowName, String action,
                                        Instant occurredAt, String definitionHash, boolean signed,
                                        boolean chainIntact) {

    public static WorkflowSealEventResponse from(WorkflowSealEvent e, boolean chainIntact) {
        return new WorkflowSealEventResponse(e.getId(), e.getWorkflowId(), e.getWorkflowName(), e.getAction().name(),
                e.getOccurredAt(), e.getDefinitionHash(), e.getSignature() != null, chainIntact);
    }
}
