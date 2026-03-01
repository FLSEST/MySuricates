import { useState } from 'react';
import { ScrollText, ChevronLeft, ChevronRight } from 'lucide-react';
import { useActivityLog } from '../hooks/useActivityLog';
import { useWorkflows } from '../hooks/useWorkflows';
import { useT } from '../i18n';
import type { ActivityLogLevel, ActivityLogCategory } from '../types';

const LEVELS: ActivityLogLevel[] = ['DEBUG', 'INFO', 'WARN', 'ERROR'];
const CATEGORIES: ActivityLogCategory[] = ['SYSTEM', 'EXECUTION', 'SCHEDULE', 'WEBHOOK', 'SECURITY', 'CONTAINER'];

const levelColors: Record<ActivityLogLevel, string> = {
  DEBUG: 'bg-slate-100 text-slate-700 dark:bg-slate-700 dark:text-slate-300',
  INFO: 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300',
  WARN: 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300',
  ERROR: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300',
};

export function ActivityLog() {
  const t = useT();
  const [page, setPage] = useState(0);
  const [level, setLevel] = useState('');
  const [category, setCategory] = useState('');
  const [workflowId, setWorkflowId] = useState('');

  const { data, isLoading } = useActivityLog(page, 50, level || undefined, category || undefined, workflowId || undefined);
  const { data: workflows } = useWorkflows();

  const formatTime = (ts: string) => {
    const d = new Date(ts);
    return d.toLocaleString();
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">{t.activityLog.title}</h1>
        <p className="text-text-secondary mt-1">{t.activityLog.subtitle}</p>
      </div>

      {/* Filters */}
      <div className="flex gap-3">
        <select
          value={level}
          onChange={e => { setLevel(e.target.value); setPage(0); }}
          className="px-3 py-2 rounded-lg border border-border bg-surface-raised text-sm"
        >
          <option value="">{t.activityLog.allLevels}</option>
          {LEVELS.map(l => <option key={l} value={l}>{l}</option>)}
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
      <div className="bg-surface-raised border border-border rounded-xl overflow-hidden">
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
                  <th className="px-4 py-3 font-medium">{t.activityLog.timestamp}</th>
                  <th className="px-4 py-3 font-medium">{t.activityLog.level}</th>
                  <th className="px-4 py-3 font-medium">{t.activityLog.category}</th>
                  <th className="px-4 py-3 font-medium">{t.activityLog.message}</th>
                  <th className="px-4 py-3 font-medium">{t.activityLog.source}</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map(entry => (
                  <tr key={entry.id} className="border-b border-border/50 hover:bg-surface">
                    <td className="px-4 py-2.5 whitespace-nowrap text-text-secondary text-xs">
                      {formatTime(entry.timestamp)}
                    </td>
                    <td className="px-4 py-2.5">
                      <span className={`inline-block px-2 py-0.5 rounded-sm text-xs font-medium ${levelColors[entry.level]}`}>
                        {entry.level}
                      </span>
                    </td>
                    <td className="px-4 py-2.5 text-xs text-text-secondary">{entry.category}</td>
                    <td className="px-4 py-2.5 max-w-md truncate">{entry.message}</td>
                    <td className="px-4 py-2.5 text-xs text-text-secondary">{entry.source || '—'}</td>
                  </tr>
                ))}
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
