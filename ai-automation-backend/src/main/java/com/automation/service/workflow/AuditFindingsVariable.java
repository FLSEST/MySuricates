package com.automation.service.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code audit_findings} (TODO 3643beac) — only for a workflow with a valid {@code metadata.audit};
 * any other run does not get the variable at all. The findings come from {@link AuditContextSource}.
 * Moved out of {@link WorkflowContextDataService} (TODO 441a4c46).
 */
@Component
@Order(50)
@RequiredArgsConstructor
class AuditFindingsVariable implements PlatformVariableSource {

    private final AuditContextSource auditContextSource;

    @Override
    public List<String> names(Request request) {
        return auditContextSource.auditKey(request.workflowMeta()) != null
                ? List.of(AuditContextSource.VARIABLE) : List.of();
    }

    @Override
    public Map<String, String> load(Request request) {
        String auditKey = auditContextSource.auditKey(request.workflowMeta());
        return Map.of(AuditContextSource.VARIABLE, auditContextSource.findings(request.projectId(), auditKey));
    }
}
