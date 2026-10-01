import { useState } from 'react';
import { History, RotateCcw, ChevronDown, ChevronRight, User, Bot, Download, Globe, Sparkles, Link2, Undo2, AlertTriangle, Target } from 'lucide-react';
import { useWorkflowVersions, useRestoreWorkflowVersion } from '../../hooks/useWorkflowVersions';
import { ConfirmModal } from '../ui';
import { useT, useI18nStore } from '../../i18n';
import { formatRelativeTime } from '../../utils/formatTime';
import type { WorkflowVersion } from '../../types';

const SOURCE_ICONS: Record<string, typeof User> = {
  user: User,
  ai: Bot,
  'pool-install': Download,
  'example-project': Download,
  'goal-guard': Target,
  import: Download,
  api: Globe,
  system: History,
  assistant: Sparkles,
  factory: Bot,
  wish: Sparkles,
  dialog: Download,
  conversion: Bot,
  'chain-template': Link2,
  'bpmn-import': Download,
  restore: Undo2,
  untracked: AlertTriangle,
};

// Colored by role (db79bc54): a person -> human, AI involved (assistant, factory, wish, dialog analysis) -> ai,
// shipped content (pool, example project, chain/goal templates) plus API and restore -> info,
// file imports and system -> neutral.
const SOURCE_COLORS: Record<string, string> = {
  user: 'bg-human-subtle text-human',
  ai: 'bg-ai-subtle text-ai',
  'pool-install': 'bg-info-subtle text-info',
  'example-project': 'bg-info-subtle text-info',
  'goal-guard': 'bg-info-subtle text-info',
  import: 'bg-surface-hover text-text-secondary',
  api: 'bg-info-subtle text-info',
  system: 'bg-surface-hover text-text-secondary',
  assistant: 'bg-ai-subtle text-ai',
  factory: 'bg-ai-subtle text-ai',
  wish: 'bg-ai-subtle text-ai',
  dialog: 'bg-ai-subtle text-ai',
  conversion: 'bg-ai-subtle text-ai',
  'chain-template': 'bg-info-subtle text-info',
  'bpmn-import': 'bg-surface-hover text-text-secondary',
  restore: 'bg-info-subtle text-info',
  untracked: 'bg-warning-subtle text-warning',
};

export default function WorkflowVersionsTab({ workflowId, currentHash }: { workflowId: string; currentHash?: string | null }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const [page, setPage] = useState(0);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  // Version whose restore waits for the confirmation (23635ef6)
  const [restoring, setRestoring] = useState<WorkflowVersion | null>(null);
  const { data, isLoading } = useWorkflowVersions(workflowId, page);
  const restoreMutation = useRestoreWorkflowVersion(workflowId);

  const versions = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-12 text-text-secondary">
        <div className="animate-spin rounded-full h-6 w-6 border-2 border-accent border-t-transparent mr-3" />
        {t.common.loading}
      </div>
    );
  }

  if (versions.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-text-secondary">
        <History className="w-10 h-10 mb-3 opacity-40" />
        <p>{t.workflows.noVersions}</p>
      </div>
    );
  }

  const handleRestore = (version: WorkflowVersion) => setRestoring(version);

  const sourceLabels = t.workflows.sourceLabels;

  return (
    <div className="space-y-1">
      {versions.map((v) => {
        const isExpanded = expandedId === v.id;
        const SourceIcon = SOURCE_ICONS[v.changeSource] ?? History;
        const sourceColor = SOURCE_COLORS[v.changeSource] ?? SOURCE_COLORS.system;

        return (
          <div key={v.id} className="border border-border rounded-lg overflow-hidden">
            <button
              onClick={() => setExpandedId(isExpanded ? null : v.id)}
              className="w-full flex items-center gap-3 px-4 py-3 hover:bg-surface-hover transition-colors text-left"
            >
              {isExpanded ? (
                <ChevronDown className="w-4 h-4 text-text-secondary flex-shrink-0" />
              ) : (
                <ChevronRight className="w-4 h-4 text-text-secondary flex-shrink-0" />
              )}

              <span className="inline-flex items-center justify-center w-7 h-7 rounded-full bg-accent/20 text-accent text-xs font-bold flex-shrink-0">
                {v.versionNumber}
              </span>

              <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${sourceColor}`}>
                <SourceIcon className="w-3 h-3" />
                {sourceLabels[v.changeSource as keyof typeof sourceLabels] ?? v.changeSource}
              </span>

              {v.definitionHash && (
                <span className="font-mono text-[11px] text-text-tertiary flex-shrink-0" title={v.definitionHash}>
                  {v.definitionHash.slice(0, 8)}
                </span>
              )}
              {currentHash && v.definitionHash === currentHash && (
                <span className="px-1.5 py-0.5 rounded-full text-[10px] font-medium bg-success-subtle text-success flex-shrink-0">
                  {t.workflows.versionCurrent}
                </span>
              )}

              {v.changeMessage && (
                <span className="text-sm text-text-secondary truncate flex-1">
                  {v.changeMessage}
                </span>
              )}

              <span className="text-xs text-text-tertiary flex-shrink-0 ml-auto">
                {formatRelativeTime(v.createdAt, locale)}
              </span>
            </button>

            {isExpanded && (
              <div className="px-4 pb-3 border-t border-border">
                <div className="flex items-center justify-between mt-3 mb-2">
                  <span className="text-xs font-medium text-text-secondary uppercase tracking-wider">
                    Snapshot
                  </span>
                  <button
                    onClick={() => handleRestore(v)}
                    disabled={restoreMutation.isPending}
                    className="inline-flex items-center gap-1.5 px-3 py-1 text-xs font-medium rounded-md
                      bg-accent/10 text-accent hover:bg-accent/20 transition-colors
                      disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    <RotateCcw className="w-3 h-3" />
                    {t.workflows.restoreVersion}
                  </button>
                </div>
                <pre className="text-xs bg-surface-secondary rounded-md p-3 overflow-x-auto max-h-80 text-text-secondary">
                  {formatSnapshot(v.snapshot)}
                </pre>
              </div>
            )}
          </div>
        );
      })}

      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 pt-4">
          <button
            onClick={() => setPage(p => Math.max(0, p - 1))}
            disabled={page === 0}
            className="px-3 py-1 text-sm rounded border border-border hover:bg-surface-hover disabled:opacity-40"
          >
            &laquo;
          </button>
          <span className="text-sm text-text-secondary">
            {page + 1} / {totalPages}
          </span>
          <button
            onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
            disabled={page >= totalPages - 1}
            className="px-3 py-1 text-sm rounded border border-border hover:bg-surface-hover disabled:opacity-40"
          >
            &raquo;
          </button>
        </div>
      )}

      <ConfirmModal
        open={restoring !== null}
        onClose={() => setRestoring(null)}
        onConfirm={() => restoring && restoreMutation.mutate(restoring.id, { onSettled: () => setRestoring(null) })}
        title={t.workflows.restoreVersion}
        message={restoring ? t.workflows.restoreConfirm.replace('{version}', String(restoring.versionNumber)) : null}
        confirmLabel={t.workflows.restoreVersion}
        confirmIcon={<RotateCcw className="w-4 h-4" />}
        isPending={restoreMutation.isPending}
      />
    </div>
  );
}

function formatSnapshot(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}
