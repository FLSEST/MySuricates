package com.automation.service.workflow;

import java.util.Map;

/**
 * Supplies the context variable {@code audit_findings} - the known findings of the own audit - for
 * a workflow with a valid {@code metadata.audit} (TODO 3643beac). Implemented in
 * {@code service.audit}: an edge {@code service.workflow -> service.audit} would close the cycle
 * {@code audit -> mapper -> workflow}; same pattern as {@link GoalContextSource}.
 */
public interface AuditContextSource {

    /** Name of the context variable. */
    String VARIABLE = "audit_findings";

    /** The audit key from {@code metadata.audit}, {@code null} without an audit - reads nothing from the DB. */
    String auditKey(Map<String, Object> workflowMeta);

    /** The {@code <audit_findings>} block of this audit in the project; throws if the query fails. */
    String findings(String projectId, String auditKey);
}
