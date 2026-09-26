import { useState } from 'react';
import { ChevronDown, ChevronRight, ClipboardCheck } from 'lucide-react';
import { useT } from '../../i18n';
import { useWorkflowAnalysis } from '../../hooks/useWorkflowAnalysis';
import type { AnalyzerFinding, AnalyzerSeverity, Workflow } from '../../types';

const SEVERITIES: AnalyzerSeverity[] = ['HIGH', 'MEDIUM', 'LOW'];

const SEVERITY_CLASS: Record<AnalyzerSeverity, string> = {
  HIGH: 'bg-danger-subtle text-danger',
  MEDIUM: 'bg-warning-subtle text-warning',
  LOW: 'bg-surface-raised text-text-secondary',
};

/**
 * The analyzer's findings on the workflow page (TODO f5153c47): until now they were only reachable via
 * REST, so a key no executor reads (`command` instead of `goal`, `on_failure: "fail"`) went unnoticed.
 * Compact — counts per severity, the list on demand — and absent when the definition is clean.
 */
export function WorkflowAnalysisNotice({ workflow }: { workflow: Workflow }) {
  const t = useT();
  const a = t.workflows.analysis;
  const [open, setOpen] = useState(false);
  const { data } = useWorkflowAnalysis(workflow.id, workflow.updatedAt);
  const findings = data?.findings ?? [];
  if (findings.length === 0) return null;

  const counts = SEVERITIES.map((severity) => ({
    severity,
    count: findings.filter((f) => f.severity === severity).length,
  })).filter((c) => c.count > 0);

  return (
    <div data-testid="workflow-analysis" className="rounded-lg border border-border bg-surface p-3">
      <div className="flex flex-wrap items-center gap-2">
        <ClipboardCheck className="w-4 h-4 text-text-secondary shrink-0" />
        <span className="text-sm font-medium text-text">
          {a.summary.replace('{count}', String(findings.length))}
        </span>
        {counts.map(({ severity, count }) => (
          <span key={severity} data-testid={`analysis-count-${severity}`}
                className={`text-xs font-medium px-1.5 py-0.5 rounded ${SEVERITY_CLASS[severity]}`}>
            {count} {a.severity[severity]}
          </span>
        ))}
        <button
          type="button"
          onClick={() => setOpen((o) => !o)}
          aria-expanded={open}
          aria-controls="workflow-analysis-list"
          className="ml-auto inline-flex items-center gap-1 text-sm text-text-secondary hover:text-text"
        >
          {open ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
          {open ? a.hide : a.show}
        </button>
      </div>
      {open && (
        <div id="workflow-analysis-list" className="mt-2">
          <p className="text-xs text-text-tertiary mb-2">{a.hint}</p>
          <ul className="space-y-1.5">
            {findings.map((finding, i) => (
              <FindingRow key={`${finding.rule}-${finding.blockIndex}-${i}`} finding={finding} />
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function FindingRow({ finding }: { finding: AnalyzerFinding }) {
  const t = useT();
  const a = t.workflows.analysis;
  const where = finding.blockIndex < 0
    ? a.definition
    : a.block.replace('{n}', String(finding.blockIndex + 1)) + (finding.blockName ? ` · ${finding.blockName}` : '');
  return (
    <li className="text-sm flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
      <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${SEVERITY_CLASS[finding.severity] ?? SEVERITY_CLASS.LOW}`}>
        {a.severity[finding.severity] ?? finding.severity}
      </span>
      <code className="text-xs text-text-secondary">{finding.rule}</code>
      <span className="text-xs text-text-secondary">{where}</span>
      <span className="basis-full text-text break-words">{finding.detail}</span>
    </li>
  );
}
