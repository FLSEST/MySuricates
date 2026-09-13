import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { PageResponse } from '../types';

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

export function useAuditFindings(projectId?: string | null, auditKey?: string | null, enabled = true) {
  const params = new URLSearchParams({ size: '200' });
  if (projectId) params.set('projectId', projectId);
  if (auditKey) params.set('auditKey', auditKey);
  return useQuery({
    queryKey: ['audit-findings', projectId ?? null, auditKey ?? null],
    queryFn: () => api.get<PageResponse<AuditFinding>>(`/audit-findings?${params}`),
    enabled,
  });
}

export function useAuditFinding(id: string | null) {
  return useQuery({
    queryKey: ['audit-finding', id],
    queryFn: () => api.get<AuditFinding>(`/audit-findings/${encodeURIComponent(id!)}`),
    enabled: !!id,
  });
}

function useFindingMutation<T>(mutationFn: (input: T) => Promise<AuditFinding>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['audits'] });
      queryClient.invalidateQueries({ queryKey: ['audit-findings'] });
    },
  });
}

export function useUpdateAuditFinding() {
  return useFindingMutation<{ id: string; status: AuditFindingStatus; dispositionNote?: string }>(
    ({ id, ...body }) => api.patch<AuditFinding>(`/audit-findings/${id}`, body),
  );
}

export function useCreateAuditTodo() {
  return useFindingMutation<string>((id) => api.post<AuditFinding>(`/audit-findings/${id}/todos`));
}

export function useLinkAuditTodo() {
  return useFindingMutation<{ findingId: string; todoId: string }>(({ findingId, todoId }) =>
    api.post<AuditFinding>(`/audit-findings/${findingId}/todo-links`, { todoId }),
  );
}
