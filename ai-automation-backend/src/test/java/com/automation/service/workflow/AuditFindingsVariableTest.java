package com.automation.service.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code audit_findings} exists only for an audit workflow (TODO 441a4c46, 3643beac). */
@ExtendWith(MockitoExtension.class)
class AuditFindingsVariableTest {

    private static final Map<String, Object> AUDIT_META = Map.of("audit", Map.of("key", "project.security"));

    @Mock private AuditContextSource auditContextSource;

    @InjectMocks private AuditFindingsVariable auditFindings;

    private static PlatformVariableSource.Request run(Map<String, Object> workflowMeta) {
        return new PlatformVariableSource.Request("wf-1", "proj-1", workflowMeta, name -> null);
    }

    @Test
    void names_onlyForAnAuditWorkflow() {
        when(auditContextSource.auditKey(AUDIT_META)).thenReturn("project.security");
        when(auditContextSource.auditKey(Map.of())).thenReturn(null);

        assertThat(auditFindings.names(run(AUDIT_META))).containsExactly(AuditContextSource.VARIABLE);
        assertThat(auditFindings.names(run(Map.of()))).isEmpty();
        verify(auditContextSource, never()).findings(any(), any());
    }

    @Test
    void load_asksForTheFindingsOfThisAuditInTheRunsProject() {
        when(auditContextSource.auditKey(AUDIT_META)).thenReturn("project.security");
        when(auditContextSource.findings("proj-1", "project.security")).thenReturn("(keine bekannten Befunde)");

        assertThat(auditFindings.load(run(AUDIT_META)))
                .containsExactlyEntriesOf(Map.of(AuditContextSource.VARIABLE, "(keine bekannten Befunde)"));
    }
}
