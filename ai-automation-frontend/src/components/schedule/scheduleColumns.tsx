import { useMemo, type ReactNode } from 'react';
import { Link2, Pencil, Trash2 } from 'lucide-react';
import { Toggle } from '../ui';
import { statusDot } from './statusDot';
import { useI18nStore, useT } from '../../i18n';
import { formatTimestamp } from '../../utils/localeFormat';
import type { ScheduledWorkflow } from '../../types';

export interface ScheduleColumn {
  key: string;
  header: string;
  render: (s: ScheduledWorkflow) => ReactNode;
  width?: string;
}

interface ScheduleColumnActions {
  onEdit: (s: ScheduledWorkflow) => void;
  onDelete: (id: string) => void;
  onToggle: (id: string, enable: boolean) => void;
}

/** Column definitions for the schedules list table. */
export function useScheduleColumns({ onEdit, onDelete, onToggle }: ScheduleColumnActions): ScheduleColumn[] {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);

  return useMemo(() => [
    {
      key: 'workflow',
      header: t.schedules.targetType,
      render: (s: ScheduledWorkflow) => (
        <div className="min-w-0 flex items-center gap-2">
          {s.targetType === 'CHAIN' && (
            <Link2 className="w-3.5 h-3.5 text-accent/70 shrink-0" />
          )}
          <div className="min-w-0">
            <span className="font-medium text-text block truncate">
              {s.targetType === 'CHAIN' ? s.chainName : s.workflowName}
            </span>
            {s.targetType === 'CHAIN' ? (
              <span className="text-xs text-accent/70 block truncate">{t.schedules.targetChain}</span>
            ) : s.projectName ? (
              <span className="text-xs text-accent/70 block truncate">{s.projectName}</span>
            ) : null}
          </div>
        </div>
      ),
    },
    {
      key: 'type',
      header: t.schedules.scheduleType,
      render: (s: ScheduledWorkflow) => (
        <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
          s.scheduleType === 'ONCE'
            ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300'
            : s.scheduleType === 'DELAYED'
            ? 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300'
            : 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300'
        }`}>
          {s.scheduleType === 'ONCE' ? t.schedules.once
            : s.scheduleType === 'DELAYED' ? t.schedules.delayed
            : t.schedules.recurring}
        </span>
      ),
      width: '120px',
    },
    {
      key: 'schedule',
      header: t.schedules.cronExpression,
      render: (s: ScheduledWorkflow) => (
        <span className="font-mono text-xs text-text-secondary">
          {s.scheduleType === 'ONCE' || s.scheduleType === 'DELAYED'
            ? (s.scheduledAt ? formatTimestamp(s.scheduledAt, locale) : '-')
            : s.cronExpression}
          {s.scheduleType === 'DELAYED' && s.delayExpression && (
            <span className="ml-1 text-orange-500">({s.delayExpression})</span>
          )}
        </span>
      ),
    },
    {
      key: 'nextRun',
      header: t.schedules.nextRun,
      render: (s: ScheduledWorkflow) => (
        <span className="text-text-secondary">
          {(s.scheduleType === 'ONCE' || s.scheduleType === 'DELAYED') && !s.enabled
            ? (<span className="text-xs px-2 py-0.5 rounded-full bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400">{t.schedules.completed}</span>)
            : s.nextRunAt ? formatTimestamp(s.nextRunAt, locale) : s.maintenance ? null : '-'}
          {/* The maintenance switch holds the schedule: no next run until it is switched off */}
          {s.maintenance && (
            <span className="block text-xs text-info" data-testid="next-run-maintenance">
              {t.schedules.maintenanceNothingStarts}
            </span>
          )}
          {/* The stored run lies in a skip-until pause: nextRunAt is already the first one after it */}
          {s.pausedUntil && (
            <span className="block text-xs text-info" data-testid="next-run-paused">
              {t.schedules.pausedUntil.replace('{until}', () => formatTimestamp(s.pausedUntil!, locale))}
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'lastRun',
      header: t.schedules.lastRun,
      render: (s: ScheduledWorkflow) => (
        <div className="flex items-center gap-2">
          {s.lastRunStatus && statusDot(s.lastRunStatus)}
          <span className="text-text-secondary">
            {s.lastRunAt ? formatTimestamp(s.lastRunAt, locale) : '-'}
          </span>
        </div>
      ),
    },
    {
      key: 'enabled',
      header: t.common.status,
      render: (s: ScheduledWorkflow) => (
        <Toggle
          enabled={s.enabled}
          onChange={(enabled) => onToggle(s.id, enabled)}
        />
      ),
      width: '80px',
    },
    {
      key: 'actions',
      header: '',
      render: (s: ScheduledWorkflow) => (
        <div className="flex items-center gap-1">
          <button
            onClick={(e) => {
              e.stopPropagation();
              onEdit(s);
            }}
            className="p-1.5 rounded-lg text-text-tertiary hover:text-accent transition-colors"
            aria-label={t.schedules.edit}
          >
            <Pencil className="w-4 h-4" />
          </button>
          <button
            onClick={(e) => {
              e.stopPropagation();
              onDelete(s.id);
            }}
            className="p-1.5 rounded-lg text-text-tertiary hover:text-danger transition-colors"
            aria-label={t.common.delete}
          >
            <Trash2 className="w-4 h-4" />
          </button>
        </div>
      ),
      width: '80px',
    },
  ], [t, locale, onEdit, onDelete, onToggle]);
}
