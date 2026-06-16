import { useT } from '../i18n';
import { useSchedules, useToggleSchedule } from '../hooks/useSchedules';
import { Spinner, Toggle } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { EmptyState } from '../components/shared/EmptyState';
import { SimplePage, SimplePageHeader, SimpleRow, StatusDot } from '../components/simple/kit';
import { AREA_COLORS } from '../utils/homeTiles';
import { formatDateTime } from '../utils/formatTime';

export function SimpleSchedules() {
  const t = useT();
  const { data: schedules, isLoading, error, refetch } = useSchedules();
  const toggle = useToggleSchedule();

  if (isLoading) {
    return <div className="flex items-center justify-center h-64"><Spinner text={t.common.loading} /></div>;
  }
  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <SimplePage>
      <SimplePageHeader title={t.simple.schedules.title} subtitle={t.simple.schedules.subtitle} />

      {(schedules?.length ?? 0) === 0 ? (
        <EmptyState title={t.simple.schedules.empty} description={t.simple.schedules.emptyHint} />
      ) : (
        <div className="space-y-2">
          {(schedules ?? []).map((s) => (
            <SimpleRow key={s.id} color={AREA_COLORS.schedules}>
              <div className="flex items-center gap-3 min-w-0">
                <StatusDot status={s.lastRunStatus ?? undefined} />
                <div className="min-w-0">
                  <p className="text-sm font-medium text-text truncate">{s.workflowName ?? s.chainName ?? '—'}</p>
                  <p className="text-xs text-text-tertiary truncate">
                    {s.scheduleType} · {t.simple.schedules.nextRun}: {s.nextRunAt ? formatDateTime(s.nextRunAt) : '—'}
                  </p>
                </div>
              </div>
              <Toggle enabled={s.enabled} onChange={(enable) => toggle.mutate({ id: s.id, enable })} />
            </SimpleRow>
          ))}
        </div>
      )}
    </SimplePage>
  );
}
