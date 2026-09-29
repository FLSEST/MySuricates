import { useNavigate } from 'react-router-dom';
import { Play, Trash2, Pencil, ArrowDown, ChevronRight } from 'lucide-react';
import { Card, Button, Badge } from '../ui';
import { useChainRuns } from '../../hooks/useChains';
import { useI18nStore, useT } from '../../i18n';
import { formatDuration, formatRelativeTime } from '../../utils/formatTime';
import type { WorkflowChain } from '../../types';
import { ChainStatusBadge } from './ChainStatusBadge';
import { SealStateIcon } from '../workflow/SealStateIcon';
import { useSealStatuses } from '../../hooks/useSealStatus';

export function ChainDetail({ chain, onExecute, onEdit, onDelete }: {
  chain: WorkflowChain;
  onExecute: () => void;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const navigate = useNavigate();
  const { data: runs } = useChainRuns(chain.id);
  const { byWorkflow: sealStatuses } = useSealStatuses();

  return (
    <div className="space-y-4">
      <Card>
        <div className="p-4">
          <div className="flex items-start justify-between mb-3">
            <div>
              <h2 className="text-xl font-bold text-text-primary flex items-center gap-2">
                <span>{chain.icon || '🔗'}</span>
                {chain.name}
              </h2>
              {chain.description && (
                <p className="text-text-secondary mt-1">{chain.description}</p>
              )}
            </div>
            <div className="flex items-center gap-2">
              <Button variant="ghost" size="sm" icon={<Pencil className="w-4 h-4" />} onClick={onEdit} title={t.common.edit} />
              <Button variant="ghost" size="sm" icon={<Trash2 className="w-4 h-4" />} onClick={onDelete} title={t.common.delete} />
              <Button icon={<Play className="w-4 h-4" />} onClick={onExecute} disabled={chain.entries.length === 0}>
                {t.chains.execute}
              </Button>
            </div>
          </div>

          {chain.stopOnFailure && (
            <Badge variant="warning">{t.chains.stopOnFailure}</Badge>
          )}
        </div>
      </Card>

      {/* Pipeline Visualization */}
      <Card>
        <div className="p-4">
          <h3 className="text-sm font-semibold text-text-secondary uppercase tracking-wide mb-3">
            {t.chains.entries} ({chain.entries.length})
          </h3>
          {chain.entries.length === 0 ? (
            <p className="text-text-tertiary text-sm">{t.chains.noEntries}</p>
          ) : (
            <div className="space-y-2">
              {[...chain.entries]
                .sort((a, b) => a.sortOrder - b.sortOrder)
                .map((entry, i) => (
                  <div key={i}>
                    {i > 0 && (
                      <div className="flex justify-center py-1">
                        <ArrowDown className="w-4 h-4 text-text-tertiary" />
                      </div>
                    )}
                    <div className="flex items-center gap-3 p-3 rounded-lg bg-surface-raised border border-border">
                      <span className="text-xs font-mono text-text-tertiary w-6 text-center">{i + 1}</span>
                      <div className="flex-1 min-w-0">
                        <p className="font-medium text-text-primary flex items-center gap-1.5 min-w-0">
                          <span className="truncate">{entry.label || entry.workflowName || entry.workflowId}</span>
                          <SealStateIcon sealed={sealStatuses.has(entry.workflowId)} status={sealStatuses.get(entry.workflowId)} />
                        </p>
                        {entry.projectName && (
                          <p className="text-xs text-text-tertiary">{entry.projectName}</p>
                        )}
                      </div>
                      {entry.stopOnFailure && (
                        <Badge variant="warning" dot>{t.chains.stopBadge}</Badge>
                      )}
                    </div>
                  </div>
                ))}
            </div>
          )}
        </div>
      </Card>

      {/* Recent Runs */}
      <Card>
        <div className="p-4">
          <h3 className="text-sm font-semibold text-text-secondary uppercase tracking-wide mb-3">
            {t.chains.runs}
          </h3>
          {(!runs || runs.length === 0) ? (
            <p className="text-text-tertiary text-sm">{t.chains.noRuns}</p>
          ) : (
            <div className="space-y-2">
              {runs.slice(0, 10).map(run => (
                <div
                  key={run.id}
                  className="flex items-center justify-between p-2 rounded-lg hover:bg-surface-raised cursor-pointer transition-colors"
                  onClick={() => navigate(`/chains/${chain.id}/run/${run.id}`)}
                >
                  <div className="flex items-center gap-3">
                    <ChainStatusBadge status={run.status} />
                    <span className="text-sm text-text-secondary">
                      {(() => {
                        const completed = run.entryRuns.filter(e => e.status === 'COMPLETED').length;
                        const failed = run.entryRuns.filter(e => e.status === 'FAILED').length;
                        const cancelled = run.entryRuns.filter(e => e.status === 'CANCELLED').length;
                        return (
                          <>
                            {completed > 0 && <span className="text-success">{completed} ✓</span>}
                            {completed > 0 && (failed > 0 || cancelled > 0) && ' '}
                            {failed > 0 && <span className="text-danger">{failed} ✗</span>}
                            {failed > 0 && cancelled > 0 && ' '}
                            {cancelled > 0 && <span className="text-warning">{cancelled} ⊘</span>}
                            {completed === 0 && failed === 0 && cancelled === 0 && '0'}
                            {' / '}{run.entryRuns.length} {t.chains.entries}
                          </>
                        );
                      })()}
                    </span>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className="text-xs text-text-tertiary">{formatDuration(run.durationMs, locale)}</span>
                    {run.startedAt && (
                      <span className="text-xs text-text-tertiary">{formatRelativeTime(run.startedAt, locale)}</span>
                    )}
                    <ChevronRight className="w-4 h-4 text-text-tertiary" />
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </Card>
    </div>
  );
}
