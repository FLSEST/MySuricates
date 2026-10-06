import { Link } from 'react-router-dom';
import { ShieldCheck } from 'lucide-react';
import { useT } from '../../i18n';
import { useModuleEnabled } from '../../hooks/useModules';
import type { AuditTodoThreshold, AuditWorkflowConfig } from '../../types';
import { AUDIT_TODO_THRESHOLDS, effectiveThreshold } from './auditWorkflow';

/**
 * The audit part of an audit workflow in the panel of the workflow list (TODO d8607f77): which audit it feeds,
 * a link to its findings, and from which severity a finding gets a TODO by itself (`audit.todoMinSeverity`).
 * Takes the place of the Auto-Todo switch, which the backend ignores for audit workflows.
 */
export function WorkflowAuditSettings({ projectId, audit, onChange }: {
  projectId: string;
  audit: AuditWorkflowConfig & { key: string };
  onChange: (audit: AuditWorkflowConfig) => void;
}) {
  const t = useT();
  const tw = t.workflows;
  const auditModule = useModuleEnabled('audit');
  const { threshold, unknown } = effectiveThreshold(audit.todoMinSeverity);
  const thresholdId = `audit-threshold-${audit.key}`;
  const centerLink = `/reports?audit=${encodeURIComponent(audit.key)}&project=${encodeURIComponent(projectId)}`;

  return (
    <div className="px-3 py-2 rounded-lg bg-surface-raised border border-border space-y-2" data-testid="workflow-audit-settings">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <span className="inline-flex items-center gap-1.5 text-sm font-medium text-text">
            <ShieldCheck className="w-4 h-4 text-accent shrink-0" aria-hidden="true" />{tw.auditWorkflow}
          </span>
          <p className="text-xs text-text-tertiary break-words">{tw.auditWorkflowDescription.replace('{key}', audit.key)}</p>
        </div>
        {auditModule && (
          <Link to={centerLink} className="shrink-0 text-xs text-accent hover:underline focus-ring rounded">
            {tw.auditOpenInCenter}
          </Link>
        )}
      </div>
      <div className="space-y-1">
        <label htmlFor={thresholdId} className="text-xs font-medium text-text-secondary">{tw.auditTodoThreshold}</label>
        <select
          id={thresholdId}
          value={threshold}
          onChange={(e) => onChange({ ...audit, todoMinSeverity: e.target.value as AuditTodoThreshold })}
          className="w-full text-xs bg-surface border border-border rounded-md px-2 py-1.5 text-text focus:outline-none focus:ring-1 focus:ring-accent"
        >
          {AUDIT_TODO_THRESHOLDS.map((value) => (
            <option key={value} value={value}>{tw.auditTodoThresholds[value]}</option>
          ))}
        </select>
        {unknown !== undefined && (
          <p className="text-xs text-warning" data-testid="audit-threshold-unknown">
            {tw.auditTodoThresholdUnknown.replace('{value}', unknown)}
          </p>
        )}
        <p className="text-xs text-text-tertiary">{tw.auditTodoThresholdHint}</p>
      </div>
    </div>
  );
}
