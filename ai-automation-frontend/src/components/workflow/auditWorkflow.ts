import type { AuditTodoThreshold, AuditWorkflowConfig, WorkflowMetadata } from '../../types';

/** Strongest first, as the select lists them. */
export const AUDIT_TODO_THRESHOLDS: readonly AuditTodoThreshold[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'NONE'];

const DEFAULT_THRESHOLD: AuditTodoThreshold = 'HIGH';

/**
 * The audit block of a workflow – only when the backend would ingest it as well (`AuditConfig.from`): enabled
 * exactly `true`, key, category and report block present. Otherwise `null`: then it is no audit workflow.
 */
export function auditConfigOf(metadata: WorkflowMetadata | undefined): (AuditWorkflowConfig & { key: string }) | null {
  const audit = metadata?.audit;
  if (!audit || audit.enabled !== true || !audit.key || !audit.category || !audit.reportBlock) return null;
  return { ...audit, key: audit.key };
}

/**
 * The threshold the backend applies to `todoMinSeverity`: without a value the default, in any case;
 * an unknown value also falls back to the default and is returned as `unknown`.
 */
export function effectiveThreshold(raw: string | undefined): { threshold: AuditTodoThreshold; unknown?: string } {
  if (raw === undefined || raw.trim() === '') return { threshold: DEFAULT_THRESHOLD };
  const normalized = raw.trim().toUpperCase();
  const known = AUDIT_TODO_THRESHOLDS.find((threshold) => threshold === normalized);
  return known ? { threshold: known } : { threshold: DEFAULT_THRESHOLD, unknown: raw };
}
