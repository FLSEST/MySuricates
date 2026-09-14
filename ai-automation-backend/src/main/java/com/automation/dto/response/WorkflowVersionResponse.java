package com.automation.dto.response;


import java.time.Instant;

/** @param definitionHash SHA-256 of the snapshot stored in this version row */
public record WorkflowVersionResponse(
        String id,
        String workflowId,
        int versionNumber,
        String snapshot,
        String changeSource,
        String changeMessage,
        Instant createdAt,
        String definitionHash
) {
    /** Backwards-compat constructor for call-sites written before definition hashes existed. */
    public WorkflowVersionResponse(String id, String workflowId, int versionNumber, String snapshot,
                                   String changeSource, String changeMessage, Instant createdAt) {
        this(id, workflowId, versionNumber, snapshot, changeSource, changeMessage, createdAt, null);
    }
}
