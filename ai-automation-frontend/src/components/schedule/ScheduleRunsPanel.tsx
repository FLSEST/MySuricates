import { History } from 'lucide-react';
import { useScheduleRuns } from '../../hooks/useSchedules';
import { useI18nStore, useT } from '../../i18n';
import { formatTimestamp } from '../../utils/localeFormat';
import { formatDuration } from '../../utils/formatTime';
import { statusDot } from './statusDot';

export function ScheduleRunsPanel({ scheduleId }: { scheduleId: string }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data, isLoading } = useScheduleRuns(scheduleId);

  if (isLoading) {
    return <div className="text-xs text-text-secondary py-1">{t.common.loading}</div>;
  }

  const workflowRuns = data?.workflowRuns ?? [];
  const chainRuns = data?.chainRuns ?? [];
  const totalRuns = workflowRuns.length + chainRuns.length;

  if (totalRuns === 0) {
    return (
      <div className="flex items-center gap-2 text-xs text-text-tertiary py-1">
        <History className="w-3.5 h-3.5" />
        {t.schedules.noRuns}
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-1.5 text-xs font-semibold text-text-secondary uppercase tracking-wider mb-2">
        <History className="w-3.5 h-3.5" />
        {t.schedules.runHistory} ({totalRuns})
      </div>
      <div className="space-y-1">
        {workflowRuns.map((run) => (
          <div key={run.id} className="flex items-center gap-3 text-xs py-1 px-2 rounded bg-surface/60">
            {statusDot(run.status)}
            <span className="text-text-secondary font-medium">{t.schedules.workflowRun}</span>
            <span className="text-text truncate">{run.workflowName}</span>
            {run.projectName && <span className="text-text-tertiary truncate">({run.projectName})</span>}
            <span className="ml-auto text-text-tertiary shrink-0">
              {run.startedAt ? formatTimestamp(run.startedAt, locale) : '-'}
            </span>
            {run.durationMs != null && (
              <span className="text-text-tertiary shrink-0">{formatDuration(run.durationMs, locale)}</span>
            )}
          </div>
        ))}
        {chainRuns.map((run) => (
          <div key={run.id} className="flex items-center gap-3 text-xs py-1 px-2 rounded bg-surface/60">
            {statusDot(run.status)}
            <span className="text-text-secondary font-medium">{t.schedules.chainRun}</span>
            <span className="text-text truncate">{run.chainName ?? run.chainId}</span>
            <span className="ml-auto text-text-tertiary shrink-0">
              {run.startedAt ? formatTimestamp(run.startedAt, locale) : '-'}
            </span>
            {run.durationMs != null && (
              <span className="text-text-tertiary shrink-0">{formatDuration(run.durationMs, locale)}</span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
