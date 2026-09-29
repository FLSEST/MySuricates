import { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { Badge } from '../ui';
import { useT } from '../../i18n';
import type { ProjectBundlePreview, ProjectBundlePreviewWorkflow } from '../../types';

function scheduleTargetLabel(target: string, preview: ProjectBundlePreview): string {
  const [kind, key] = target.split(':', 2);
  if (kind === 'workflow') return preview.workflows.find((w) => w.key === key)?.name ?? target;
  if (kind === 'chain') return preview.chains.find((c) => c.key === key)?.name ?? target;
  return target;
}

function WorkflowRow({ wf }: { wf: ProjectBundlePreviewWorkflow }) {
  const t = useT();
  const [expanded, setExpanded] = useState(false);
  const hasShell = wf.shellCommands.length > 0;

  return (
    <div className="border border-border rounded-lg">
      <div className="flex items-start justify-between gap-2 px-3 py-2">
        <div className="min-w-0">
          <p className="text-sm font-medium text-text truncate">{wf.name}</p>
          <p className="text-xs text-text-tertiary">
            {t.projects.bundle.blockCount.replace('{count}', String(wf.blockCount))}
            {wf.blockTypes.length > 0 ? ` · ${wf.blockTypes.join(', ')}` : ''}
          </p>
          {(wf.providers.length > 0 || wf.models.length > 0) && (
            <p className="text-xs text-text-tertiary">
              {wf.providers.length > 0 && `${t.projects.bundle.providers}: ${wf.providers.join(', ')}`}
              {wf.providers.length > 0 && wf.models.length > 0 ? ' · ' : ''}
              {wf.models.length > 0 && `${t.projects.bundle.models}: ${wf.models.join(', ')}`}
            </p>
          )}
        </div>
        {wf.nameTakenInTarget && (
          <Badge variant="warning">{t.projects.bundle.nameTakenInTarget}</Badge>
        )}
      </div>
      {hasShell && (
        <div className="border-t border-border">
          <button
            type="button"
            onClick={() => setExpanded((e) => !e)}
            aria-expanded={expanded}
            className="w-full flex items-center gap-1.5 px-3 py-1.5 text-xs text-text-secondary hover:bg-surface-raised transition-colors"
          >
            {expanded ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
            {t.projects.bundle.shellCommands} ({wf.shellCommands.length})
          </button>
          {expanded && (
            <div className="px-3 pb-2 space-y-1.5">
              <p className="text-xs text-warning">{t.projects.bundle.shellTrustHint}</p>
              {wf.shellCommands.map((cmd, i) => (
                <pre
                  key={i}
                  className="text-xs font-mono bg-surface rounded-md p-2 overflow-x-auto text-text-secondary whitespace-pre-wrap"
                >
                  {cmd}
                </pre>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** Workflows/chains/schedules of a parsed bundle, as `POST /projects/import/preview` answered. */
export function BundleImportPreview({ preview }: { preview: ProjectBundlePreview }) {
  const t = useT();

  return (
    <div className="space-y-4">
      <div className="space-y-2">
        <p className="text-sm font-medium text-text">{t.projects.bundle.workflowsSection}</p>
        {preview.workflows.length === 0 ? (
          <p className="text-xs text-text-tertiary">{t.projects.bundle.noWorkflows}</p>
        ) : (
          <div className="space-y-2">
            {preview.workflows.map((wf) => <WorkflowRow key={wf.key} wf={wf} />)}
          </div>
        )}
      </div>

      {preview.chains.length > 0 && (
        <div className="space-y-2">
          <p className="text-sm font-medium text-text">{t.projects.bundle.chainsSection}</p>
          <ul className="space-y-1 text-sm text-text-secondary">
            {preview.chains.map((c) => (
              <li key={c.key} className="flex items-center justify-between">
                <span>{c.name}</span>
                <span className="text-xs text-text-tertiary">{c.entries}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {preview.schedules.length > 0 && (
        <div className="space-y-2">
          <p className="text-sm font-medium text-text">{t.projects.bundle.schedulesSection}</p>
          <p className="text-xs text-text-tertiary">{t.projects.bundle.schedulesDisabledHint}</p>
          <ul className="space-y-1 text-sm text-text-secondary">
            {preview.schedules.map((s, i) => (
              <li key={i} className="flex items-center justify-between">
                <span>{scheduleTargetLabel(s.target, preview)}</span>
                <span className="text-xs font-mono text-text-tertiary">{s.cronExpression ?? ''}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {preview.warnings.length > 0 && (
        <div className="space-y-1.5">
          <p className="text-sm font-medium text-warning">{t.projects.bundle.warningsSection}</p>
          <ul className="space-y-1 text-xs text-text-secondary list-disc list-inside">
            {preview.warnings.map((w, i) => <li key={i}>{w}</li>)}
          </ul>
        </div>
      )}
    </div>
  );
}
