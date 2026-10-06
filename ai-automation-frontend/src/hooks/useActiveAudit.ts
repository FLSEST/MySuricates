import { useState } from 'react';
import { type AuditSummary, useAuditFinding, useAudits } from './useAuditCenter';

/**
 * The audits of the Audit Center and the one it has open. Only project and key are kept; the
 * summary comes from the current `/audits` list, so counts that change after a finding was filed
 * reach the open audit instead of a copy taken at the click (46a94a34). A deep link
 * (`?finding=<id>`) opens the audit of its finding; `?audit=<key>&project=<id>` opens that audit (from the
 * workflow list, d8607f77). A click in the overview wins over the audit link.
 */
export function useActiveAudit(requestedFinding: string | null,
                               requestedAudit: Pick<AuditSummary, 'projectId' | 'auditKey'> | null = null) {
  const { data: audits, isLoading } = useAudits();
  const linkedFinding = useAuditFinding(requestedFinding);
  const [selectedKey, setSelectedKey] = useState<Pick<AuditSummary, 'projectId' | 'auditKey'> | null>(null);
  const key = requestedFinding ? linkedFinding.data ?? null : selectedKey ?? requestedAudit;
  const activeAudit = (key && audits?.find((audit) => audit.projectId === key.projectId && audit.auditKey === key.auditKey)) ?? null;
  return {
    audits: audits ?? [],
    loading: isLoading,
    activeAudit,
    /** Status of the deep-linked finding — its chip starts switched on. */
    linkedStatus: requestedFinding ? linkedFinding.data?.status : undefined,
    // The audit of the link is only known once both are there: the finding can arrive before the list (d2e66569)
    linkPending: linkedFinding.isLoading || isLoading,
    select: (audit: AuditSummary) => setSelectedKey({ projectId: audit.projectId, auditKey: audit.auditKey }),
  };
}
