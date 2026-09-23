import { Hint } from '../components/hints/Hint';
import { useState, Fragment } from 'react';
import { Link } from 'react-router-dom';
import { ScrollText, ChevronLeft, ChevronRight, ChevronDown, ChevronRight as ChevronRightIcon } from 'lucide-react';
import { useActivityLog } from '../hooks/useActivityLog';
import { useWorkflows } from '../hooks/useWorkflows';
import { useT } from '../i18n';
import type { ActivityLogLevel, ActivityLogCategory } from '../types';

const LEVELS: ActivityLogLevel[] = ['DEBUG', 'INFO', 'WARN', 'ERROR'];
const CATEGORIES: ActivityLogCategory[] = ['SYSTEM', 'EXECUTION', 'SCHEDULE', 'WEBHOOK', 'SECURITY', 'CONTAINER', 'PLUGIN'];

const levelColors: Record<ActivityLogLevel, string> = {
  DEBUG: 'bg-surface-hover text-text-secondary',
  INFO: 'bg-info-subtle text-info',
  WARN: 'bg-warning-subtle text-warning',
  ERROR: 'bg-danger-subtle text-danger',
};

function relativeTime(ts: string): string {
  const diff = Date.now() - new Date(ts).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'gerade';
  if (mins < 60) return `${mins} min`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h`;
  const days = Math.floor(hours / 24);
  return `${days}d`;
}

function referenceLink(category: ActivityLogCategory, refId: string | null): string | null {
  if (!refId) return null;
  switch (category) {
    case 'EXECUTION': return `/runs/${refId}`;
    case 'SCHEDULE': return '/schedules';
    case 'WEBHOOK': return '/webhooks';
    default: return null;
  }
}

export function ActivityLog() {
  const t = useT();
  const [page, setPage] = useState(0);
  const [level, setLevel] = useState('');
  const [category, setCategory] = useState('');
  const [workflowId, setWorkflowId] = useState('');
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());

  const { data, isLoading } = useActivityLog(page, 50, level || undefined, category || undefined, workflowId || undefined);
  const { data: workflows } = useWorkflows();

  const toggleExpand = (id: string) => setExpandedIds(prev => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  const formatTime = (ts: string) => new Date(ts).toLocaleString();

  const hasDetails = (entry: { metadata?: Record<string, unknown>; referenceId?: string | null }) =>
    (entry.referenceId) || (entry.metadata && Object.keys(entry.metadata).length > 0);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">{t.activityLog.title}</h1>
        <p className="text-text-secondary mt-1">{t.activityLog.subtitle}</p>
      </div>

      <Hint id="activity-events" />

      {/* Filters */}
      <div className="flex gap-3">
        <select
          value={level}
          onChange={e => { setLevel(e.target.value); setPage(0); }}
          className="px-3 py-2 rounded-lg border border-border bg-surface-raised text-sm"
          title={t.activityLog.levelExactHint}
        >
          <option value="">{t.activityLog.allLevels}</option>
          {/* The API filters for exactly this level, not "this level and above" */}
          {LEVELS.map(l => <option key={l} value={l}>{t.activityLog.levelOnly.replace('{level}', l)}</option>)}
        </select>
        <select
          value={category}
          onChange={e => { setCategory(e.target.value); setPage(0); }}
          className="px-3 py-2 rounded-lg border border-border bg-surface-raised text-sm"
        >
          <option value="">{t.activityLog.allCategories}</option>
          {CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
        </select>
        <select
          value={workflowId}
          onChange={e => { setWorkflowId(e.target.value); setPage(0); }}
          className="px-3 py-2 rounded-lg border border-border bg-surface-raised text-sm"
        >
          <option value="">{t.activityLog.allWorkflows}</option>
          {workflows?.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
        </select>
      </div>

      {/* Table */}
      <div className="bg-surface-raised border border-card-border rounded-xl overflow-hidden">
        {isLoading ? (
          <div className="p-8 text-center text-text-secondary">{t.common.loading}</div>
        ) : !data || data.content.length === 0 ? (
          <div className="p-8 text-center text-text-secondary flex flex-col items-center gap-2">
            <ScrollText className="w-8 h-8 opacity-40" />
            {t.activityLog.noLogs}
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-left text-text-secondary">
                  <th className="w-8 px-2 py-3" />
                  <th className="px-4 py-3 font-medium">{t.activityLog.timestamp}</th>
                  <th className="px-4 py-3 font-medium">{t.activityLog.level}</th>
                  <th className="px-4 py-3 font-medium">{t.activityLog.category}</th>
                  <th className="px-4 py-3 font-medium">{t.activityLog.message}</th>
                  <th className="px-4 py-3 font-medium">{t.activityLog.source}</th>
                </tr>
              </thead>
              <tbody>
                {(() => {
                  const rows: Array<{ entry: typeof data.content[0]; count: number; firstId: string }> = [];
                  for (const entry of data.content) {
                    const prev = rows[rows.length - 1];
                    if (prev && prev.entry.message === entry.message && prev.entry.level === entry.level && prev.entry.category === entry.category) {
                      prev.count++;
                    } else {
                      rows.push({ entry, count: 1, firstId: entry.id });
                    }
                  }
                  return rows.map(({ entry, count, firstId }) => {
                    const isExpanded = expandedIds.has(firstId);
                    const expandable = hasDetails(entry) || entry.message.length > 60;
                    return (
                      <Fragment key={firstId}>
                        <tr
                          className={`border-b border-border/50 hover:bg-surface ${expandable ? 'cursor-pointer' : ''}`}
                          onClick={() => expandable && toggleExpand(firstId)}
                        >
                          <td className="px-2 py-2.5 text-text-tertiary">
                            {expandable && (
                              isExpanded
                                ? <ChevronDown className="w-3.5 h-3.5" />
                                : <ChevronRightIcon className="w-3.5 h-3.5" />
                            )}
                          </td>
                          <td className="px-4 py-2.5 whitespace-nowrap text-xs">
                            <span className="text-text-secondary" title={formatTime(entry.timestamp)}>
                              {relativeTime(entry.timestamp)}
                            </span>
                          </td>
                          <td className="px-4 py-2.5">
                            <span className={`inline-block px-2 py-0.5 rounded-sm text-xs font-medium ${levelColors[entry.level]}`}>
                              {entry.level}
                            </span>
                          </td>
                          <td className="px-4 py-2.5 text-xs text-text-secondary">{entry.category}</td>
                          <td className={`px-4 py-2.5 ${isExpanded ? '' : 'max-w-md truncate'}`}>
                            {entry.message}
                            {count > 1 && (
                              <span className="ml-2 inline-flex items-center px-1.5 py-0.5 rounded-full text-xs font-medium bg-surface-raised text-text-secondary">
                                &times;{count}
                              </span>
                            )}
                          </td>
                          <td className="px-4 py-2.5 text-xs text-text-secondary">{entry.source || '—'}</td>
                        </tr>
                        {isExpanded && (
                          <tr className="bg-surface/50">
                            <td colSpan={6} className="px-6 py-3 space-y-2">
                              <p className="text-sm text-text whitespace-pre-wrap break-words">{entry.message}</p>
                              {entry.referenceId && referenceLink(entry.category, entry.referenceId) && (
                                <Link
                                  to={referenceLink(entry.category, entry.referenceId)!}
                                  onClick={e => e.stopPropagation()}
                                  className="inline-flex items-center gap-1 text-xs text-accent hover:underline"
                                >
                                  {t.activityLog.reference}: {entry.referenceId.slice(0, 8)}...
                                </Link>
                              )}
                              {entry.metadata && Object.keys(entry.metadata).length > 0 && (
                                <div className="mt-1">
                                  <p className="text-xs font-medium text-text-tertiary mb-1">{t.activityLog.metadata}</p>
                                  <div className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-0.5 text-xs">
                                    {Object.entries(entry.metadata).map(([k, v]) => (
                                      <Fragment key={k}>
                                        <span className="text-text-tertiary font-medium">{k}</span>
                                        <span className="text-text-secondary break-all">{typeof v === 'object' ? JSON.stringify(v) : String(v)}</span>
                                      </Fragment>
                                    ))}
                                  </div>
                                </div>
                              )}
                              <p className="text-[10px] text-text-tertiary">{formatTime(entry.timestamp)}</p>
                            </td>
                          </tr>
                        )}
                      </Fragment>
                    );
                  });
                })()}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Pagination */}
      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between">
          <span className="text-sm text-text-secondary">
            {data.totalElements} entries
          </span>
          <div className="flex items-center gap-2">
            <button
              onClick={() => setPage(p => Math.max(0, p - 1))}
              disabled={page === 0}
              className="p-2 rounded-lg border border-border hover:bg-surface disabled:opacity-30"
            >
              <ChevronLeft className="w-4 h-4" />
            </button>
            <span className="text-sm px-2">
              {page + 1} / {data.totalPages}
            </span>
            <button
              onClick={() => setPage(p => Math.min(data.totalPages - 1, p + 1))}
              disabled={page >= data.totalPages - 1}
              className="p-2 rounded-lg border border-border hover:bg-surface disabled:opacity-30"
            >
              <ChevronRight className="w-4 h-4" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
