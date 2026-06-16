import { useT } from '../i18n';
import { useActivityLog } from '../hooks/useActivityLog';
import { Spinner } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { EmptyState } from '../components/shared/EmptyState';
import { SimplePage, SimplePageHeader, SimpleRow } from '../components/simple/kit';
import { AREA_COLORS } from '../utils/homeTiles';
import { levelColor, relativeTime } from '../utils/activityLevel';

export function SimpleActivity() {
  const t = useT();
  const { data: page, isLoading, error, refetch } = useActivityLog(0, 50);
  const entries = page?.content ?? [];
  const now = new Date();

  if (isLoading) {
    return <div className="flex items-center justify-center h-64"><Spinner text={t.common.loading} /></div>;
  }
  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <SimplePage>
      <SimplePageHeader title={t.simple.activity.title} subtitle={t.simple.activity.subtitle} />

      {entries.length === 0 ? (
        <EmptyState title={t.simple.activity.empty} description={t.simple.activity.emptyHint} />
      ) : (
        <div className="space-y-1.5">
          {entries.map((e) => (
            <SimpleRow key={e.id} color={AREA_COLORS.activity}>
              <div className="flex items-center gap-3 min-w-0">
                <span className="w-[7px] h-[7px] rounded-full shrink-0" style={{ background: levelColor(e.level) }} />
                <div className="min-w-0">
                  <p className="text-sm text-text truncate">{e.message}</p>
                  <p className="text-xs text-text-tertiary truncate">{e.category} · {relativeTime(e.timestamp, now)}</p>
                </div>
              </div>
            </SimpleRow>
          ))}
        </div>
      )}
    </SimplePage>
  );
}
