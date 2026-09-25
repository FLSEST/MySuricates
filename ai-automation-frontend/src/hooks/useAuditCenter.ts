import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useT } from '../i18n';
import type { PageResponse, TriggerSource } from '../types';
import { useToast } from './useToast';

export type GateStatus = 'GREEN' | 'YELLOW' | 'RED';
export type AuditIngestionStatus = 'VALID' | 'INVALID';
export type AuditSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';
export type AuditFindingStatus = 'OPEN' | 'AWAITING_VERIFICATION' | 'RESOLVED' | 'ACCEPTED_RISK' | 'DISMISSED';

export interface AuditSummary {
  projectId: string;
  auditKey: string;
  category: string;
  title: string;
  workflowId: string;
  latestRunId: string;
  latestReportId: string;
  gateStatus: GateStatus | null;
  gateDescription: string | null;
  generatedAt: string;
  latestAttemptStatus: AuditIngestionStatus;
  latestAttemptError: string | null;
  trend: 'NEW' | 'IMPROVED' | 'REGRESSED' | 'UNCHANGED';
  stale: boolean;
  findingsByStatus: Record<string, number>;
  openFindingsBySeverity: Record<string, number>;
}

export interface AuditReport {
  id: string;
  projectId: string;
  workflowId: string;
  workflowRunId: string;
  auditKey: string;
  category: string;
  title: string;
  markdownContent: string;
  gateStatus: GateStatus | null;
  gateDescription: string | null;
  provider: string | null;
  model: string | null;
  workflowVersion: string | null;
  ingestionStatus: AuditIngestionStatus;
  ingestionError: string | null;
  generatedAt: string;
  findingCount: number;
  // Where the report's run came from (a3b89355) — each null when unknown, absent on older backends.
  /** Effective trigger: for a chain entry what started the chain; old chain runs say CHAIN. */
  triggerSource?: TriggerSource | null;
  /** Schedule that started the run, for a chain entry the chain's schedule. */
  scheduleId?: string | null;
  chainRunId?: string | null;
  /** Null when the chain run is gone, even if `chainRunId` is set. */
  chainId?: string | null;
  chainName?: string | null;
  /** Number of the workflow version whose definition the run executed. */
  definitionVersionNumber?: number | null;
}

export interface AuditFinding {
  id: string;
  projectId: string;
  auditKey: string;
  fingerprint: string;
  ruleKey: string;
  subject: string;
  severity: AuditSeverity;
  title: string;
  description: string | null;
  evidence: string | null;
  tags: string[];
  status: AuditFindingStatus;
  firstSeenRunId: string;
  lastSeenRunId: string;
  firstSeenAt: string;
  lastSeenAt: string;
  occurrenceCount: number;
  regressionCount: number;
  resolvedAt: string | null;
  dispositionNote: string | null;
  dispositionEvidence: string | null;
  dispositionAt: string | null;
  /** ruleKey ausserhalb von audit/rule-keys.json — die Regel-Identitaet ist ungeprueft. */
  unknownRuleKey: boolean;
  /** Evidence nennt keinen Messwert des eigenen Laufs — Finding bleibt sichtbar, bekommt aber kein verwaltetes TODO. */
  unverifiedEvidence: boolean;
  todos: Array<{ id: string; title: string; status: string; managed: boolean }>;
}

export function useAudits() {
  return useQuery({
    queryKey: ['audits'],
    queryFn: () => api.get<AuditSummary[]>('/audits'),
  });
}

export function useAuditReports(projectId: string | null, auditKey: string | null) {
  return useQuery({
    queryKey: ['audit-reports', projectId, auditKey],
    queryFn: () => api.get<PageResponse<AuditReport>>(
      `/audits/${encodeURIComponent(auditKey!)}/reports?projectId=${encodeURIComponent(projectId!)}&size=50`,
    ),
    enabled: !!projectId && !!auditKey,
  });
}

/**
 * The reports of an audit and the one to show: the report the summary names (`latestReportId`, the
 * newest valid one); if the page lacks it, the first valid one.
 */
export function useShownAuditReport(audit: AuditSummary) {
  const query = useAuditReports(audit.projectId, audit.auditKey);
  const reports = query.data?.content ?? [];
  const shown = reports.find((report) => report.id === audit.latestReportId)
    ?? reports.find((report) => report.ingestionStatus === 'VALID');
  return { reports, shown, isLoading: query.isLoading };
}

/** Findings loaded per status; the chip counts come from `AuditSummary.findingsByStatus`, not from here. */
export const FINDINGS_PAGE_SIZE = 200;

const SEVERITY_RANK: Record<AuditSeverity, number> = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };

/** The order of `AuditCenterService.listFindings`: most severe first, then the most recently seen. */
function bySeverityThenLastSeen(a: AuditFinding, b: AuditFinding): number {
  return SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity]
    || new Date(b.lastSeenAt).getTime() - new Date(a.lastSeenAt).getTime();
}

/**
 * The findings of one audit in the given statuses: one request per status, so a status with many
 * findings cannot push the others off the page (test coverage: 248 findings, 202 of them resolved).
 * Only findings whose current status is asked for come back — one that was just filed elsewhere
 * leaves the list as soon as its cached page carries the new status.
 */
export function useAuditFindingsByStatus(projectId: string, auditKey: string, statuses: readonly AuditFindingStatus[]) {
  return useQueries({
    queries: statuses.map((status) => ({
      queryKey: ['audit-findings', projectId, auditKey, status],
      queryFn: () => api.get<PageResponse<AuditFinding>>(
        `/audit-findings?${new URLSearchParams({ projectId, auditKey, status, size: String(FINDINGS_PAGE_SIZE) })}`,
      ),
    })),
    combine: (results) => {
      const byId = new Map<string, AuditFinding>();
      for (const result of results) {
        for (const finding of result.data?.content ?? []) {
          if (statuses.includes(finding.status) && !byId.has(finding.id)) byId.set(finding.id, finding);
        }
      }
      return {
        findings: [...byId.values()].sort(bySeverityThenLastSeen),
        /** A status has more findings than one page holds. */
        capped: results.some((result) => !!result.data && result.data.totalElements > result.data.content.length),
        isLoading: results.some((result) => result.isLoading),
      };
    },
  });
}

export function useAuditFinding(id: string | null) {
  return useQuery({
    queryKey: ['audit-finding', id],
    queryFn: () => api.get<AuditFinding>(`/audit-findings/${encodeURIComponent(id!)}`),
    enabled: !!id,
  });
}

/** What failed, for the toast — the server's reason follows it (a TODO id that does not exist, …). */
type FindingFailure = 'fileFailed' | 'createTodoFailed' | 'linkTodoFailed';

function useFindingMutation<T>(mutationFn: (input: T) => Promise<AuditFinding>, failure: FindingFailure) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn,
    onError: (err) => toast.error(apiErrorMessage(err, t.auditCenter[failure])),
    onSuccess: (updated) => {
      // The answer goes into every cached page at once: a finding filed under another status drops
      // out of the filtered list now instead of snapping back until the refetch arrives.
      queryClient.setQueriesData<PageResponse<AuditFinding>>({ queryKey: ['audit-findings'] }, (page) => page && {
        ...page,
        content: page.content.map((finding) => (finding.id === updated.id ? updated : finding)),
      });
      // A deep link takes its first chips from this entry; a stale status would hide the finding on return.
      queryClient.setQueryData(['audit-finding', updated.id], updated);
      queryClient.invalidateQueries({ queryKey: ['audits'] });
      queryClient.invalidateQueries({ queryKey: ['audit-findings'] });
    },
  });
}

export function useUpdateAuditFinding() {
  return useFindingMutation<{ id: string; status: AuditFindingStatus; dispositionNote?: string }>(
    ({ id, ...body }) => api.patch<AuditFinding>(`/audit-findings/${id}`, body), 'fileFailed',
  );
}

export function useCreateAuditTodo() {
  return useFindingMutation<string>((id) => api.post<AuditFinding>(`/audit-findings/${id}/todos`), 'createTodoFailed');
}

export function useLinkAuditTodo() {
  return useFindingMutation<{ findingId: string; todoId: string }>(({ findingId, todoId }) =>
    api.post<AuditFinding>(`/audit-findings/${findingId}/todo-links`, { todoId }), 'linkTodoFailed',
  );
}
