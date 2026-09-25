package com.automation.service.workflow;

import java.util.Map;

/**
 * Liefert die Kontextvariable {@code audit_findings} – die bekannten Befunde des eigenen Audits – fuer
 * einen Workflow mit gueltigem {@code metadata.audit} (TODO 3643beac). Umgesetzt in
 * {@code service.audit}: Eine Kante {@code service.workflow -> service.audit} schloesse den Zyklus
 * {@code audit -> mapper -> workflow}; dasselbe Muster wie {@link GoalContextSource}.
 */
public interface AuditContextSource {

    /** Name der Kontextvariable. */
    String VARIABLE = "audit_findings";

    /** Der Audit-Schluessel aus {@code metadata.audit}, {@code null} ohne Audit – liest nichts aus der DB. */
    String auditKey(Map<String, Object> workflowMeta);

    /** Der Block {@code <audit_findings>} dieses Audits im Projekt; wirft, wenn die Abfrage scheitert. */
    String findings(String projectId, String auditKey);
}
