import { Link } from 'react-router-dom';
import { ShieldCheck } from 'lucide-react';
import { useT } from '../../i18n';
import { useModuleEnabled } from '../../hooks/useModules';
import type { Workflow } from '../../types';
import { auditConfigOf, effectiveThreshold } from './auditWorkflow';

/**
 * Says at the top of a workflow that it is an audit (TODO f529190a): which audit it feeds and from which severity
 * its findings become TODOs by themselves. A link to the findings while the audit module is on. Nothing for any
 * other workflow – the same check as the backend's ingestion (`auditConfigOf`).
 */
export function AuditLabel({ workflow }: { workflow: Pick<Workflow, 'metadata' | 'projectId'> }) {
  const t = useT();
  const tw = t.workflows;
  const auditModule = useModuleEnabled('audit');
  const audit = auditConfigOf(workflow.metadata);
  if (!audit) return null;

  const { threshold } = effectiveThreshold(audit.todoMinSeverity);
  const todos = threshold === 'NONE'
    ? tw.auditLabelTodosNone
    : tw.auditLabelTodos.replace('{threshold}', tw.auditLabelThresholds[threshold]);
  const title = tw.auditWorkflowDescription.replace('{key}', audit.key);
  const content = (
    <>
      <ShieldCheck className="w-3.5 h-3.5 shrink-0" aria-hidden="true" />
      <span className="font-semibold">{tw.auditLabel}</span>
      <span className="text-accent/50" aria-hidden="true">·</span>
      <span className="font-mono truncate">{audit.key}</span>
      <span className="text-accent/50" aria-hidden="true">·</span>
      <span className="whitespace-nowrap">{todos}</span>
    </>
  );
  const className = 'inline-flex items-center gap-1.5 max-w-full px-2 py-0.5 rounded-md border border-accent/30 bg-accent/10 text-accent text-xs';

  if (!auditModule) {
    return <span className={className} title={title} data-testid="audit-label">{content}</span>;
  }
  const centerLink = `/reports?audit=${encodeURIComponent(audit.key)}&project=${encodeURIComponent(workflow.projectId)}`;
  return (
    <Link to={centerLink} className={`${className} hover:bg-accent/15 focus-ring`} title={`${title} ${tw.auditOpenInCenter}`}
      data-testid="audit-label" onClick={(e) => e.stopPropagation()}>
      {content}
    </Link>
  );
}
