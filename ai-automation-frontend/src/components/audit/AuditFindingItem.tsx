import type { Ref } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, Link2, Plus } from 'lucide-react';
import { Button } from '../ui';
import type { AuditFinding, AuditFindingStatus } from '../../hooks/useAuditCenter';
import type { FindingActions } from '../../hooks/useFindingActions';
import { useT, type Locale } from '../../i18n';
import { todoDeepLink } from '../../utils/todoLinks';
import { FindingStatusBadge, SeverityBadge } from './AuditParts';
import { FindingDisposition } from './FindingDisposition';

const FINDING_STATUSES: AuditFindingStatus[] = ['OPEN', 'AWAITING_VERIFICATION', 'ACCEPTED_RISK', 'DISMISSED', 'RESOLVED'];

/** One finding: what, where, evidence, disposition and TODOs — and filing it, with the forms for reason and TODO link. */
export function AuditFindingItem({ finding, actions, highlighted, workflowId, locale, sectionRef }: {
  finding: AuditFinding; actions: FindingActions; highlighted: boolean; workflowId: string; locale: Locale;
  /** Set on the deep-linked finding, which the list scrolls into view. */
  sectionRef?: Ref<HTMLElement>;
}) {
  const t = useT();
  const { disposing } = actions;
  const hasManagedTodo = finding.todos.some((todo) => todo.managed);
  return <section ref={sectionRef} className={`py-5 scroll-mt-4 ${highlighted ? 'bg-accent-subtle/30 px-3 -mx-3' : ''}`}>
    <div className="flex items-start justify-between gap-4">
      <div className="min-w-0">
        <div className="flex items-center gap-2 flex-wrap"><SeverityBadge severity={finding.severity} /><FindingStatusBadge status={finding.status} /><h3 className="font-medium text-text">{finding.title}</h3></div>
        <p className="text-sm text-text-secondary mt-2">{finding.description}</p>
        {finding.evidence && <pre className="text-xs text-text-secondary font-mono whitespace-pre-wrap mt-3 border-l-2 border-border pl-3">{finding.evidence}</pre>}
        <FindingDisposition finding={finding} locale={locale} />
        <div className="flex items-center gap-3 mt-3 text-xs text-text-tertiary flex-wrap">
          <span>{finding.ruleKey} · {finding.subject}</span>
          {finding.unknownRuleKey && <span
            className="inline-flex items-center gap-1 text-warning border border-warning/40 rounded px-1.5 py-0.5"
            title={t.auditCenter.unknownRuleKeyHint} data-testid="unknown-rule-key">
            <AlertTriangle className="w-3 h-3" /> {t.auditCenter.unknownRuleKey}
          </span>}
          {finding.unverifiedEvidence && <span
            className="inline-flex items-center gap-1 text-warning border border-warning/40 rounded px-1.5 py-0.5"
            title={t.auditCenter.unverifiedEvidenceHint} data-testid="unverified-evidence">
            <AlertTriangle className="w-3 h-3" /> {t.auditCenter.unverifiedEvidence}
          </span>}
          <span>{t.auditCenter.seenTimes.replace('{count}', String(finding.occurrenceCount))}</span>
          {finding.regressionCount > 0 && <span className="text-danger">{t.auditCenter.regressions.replace('{count}', String(finding.regressionCount))}</span>}
          <Link className="text-accent hover:underline" to={`/workflows/${workflowId}/run/${finding.lastSeenRunId}`}>{t.auditCenter.evidenceRun}</Link>
        </div>
        {finding.todos.length > 0 && <div className="flex items-center gap-2 mt-3 flex-wrap">
          {finding.todos.map((todo) => <Link key={todo.id} to={todoDeepLink(todo)} className="inline-flex items-center gap-1 text-xs text-accent hover:underline">
            <Link2 className="w-3 h-3" /> {todo.title} · {todo.status}{todo.managed ? ` · ${t.auditCenter.managed}` : ''}
          </Link>)}
        </div>}
      </div>
      <div className="flex items-center gap-2 shrink-0 flex-wrap justify-end">
        {!hasManagedTodo && !finding.status.includes('RESOLVED') && <Button size="sm" variant="secondary" icon={<Plus className="w-4 h-4" />} loading={actions.creatingTodo} onClick={() => actions.createTodo(finding.id)}>{t.auditCenter.createTodo}</Button>}
        <select className="bg-surface-raised border border-border rounded-md px-2 py-1.5 text-xs text-text" value={finding.status}
          data-testid="finding-status"
          onChange={(event) => actions.chooseStatus(finding, event.target.value as AuditFindingStatus)}>
          {FINDING_STATUSES.map((status) => <option key={status} value={status}>{t.auditCenter.statusOptions[status]}</option>)}
        </select>
        <button className="p-2 text-text-tertiary hover:text-text" title={t.auditCenter.linkTodoTitle} onClick={() => actions.toggleLinking(finding.id)}><Link2 className="w-4 h-4" /></button>
      </div>
    </div>
    {disposing?.findingId === finding.id && <form className="mt-4 max-w-xl space-y-2" data-testid="disposition-form"
      onSubmit={(event) => { event.preventDefault(); actions.saveDisposition(); }}>
      <label className="block text-xs text-text-secondary" htmlFor={`disposition-${finding.id}`}>
        {t.auditCenter.dispositionPrompt.replace('{status}', t.auditCenter.statusOptions[disposing.status])}
      </label>
      <textarea id={`disposition-${finding.id}`} rows={3} value={disposing.note}
        onChange={(event) => actions.setDispositionNote(event.target.value)}
        placeholder={t.auditCenter.dispositionPlaceholder}
        className="w-full bg-surface-raised border border-border rounded-md px-3 py-1.5 text-sm text-text" />
      <div className="flex gap-2">
        <Button size="sm" type="submit" loading={actions.saving}>{t.auditCenter.dispositionSave}</Button>
        <Button size="sm" type="button" variant="ghost" onClick={actions.cancelDisposition}>{t.common.cancel}</Button>
      </div>
    </form>}
    {actions.linking === finding.id && <form className="mt-4 flex gap-2 max-w-md" onSubmit={(event) => { event.preventDefault(); actions.submitLink(finding.id); }}>
      <input className="flex-1 bg-surface-raised border border-border rounded-md px-3 py-1.5 text-sm text-text" placeholder={t.auditCenter.linkTodoPlaceholder} value={actions.todoId} onChange={(event) => actions.setTodoId(event.target.value)} />
      <Button size="sm" type="submit" loading={actions.linkPending}>{t.auditCenter.linkTodo}</Button>
    </form>}
  </section>;
}
