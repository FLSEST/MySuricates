import { Card, CardHeader } from '../ui';
import { useT } from '../../i18n';
import type { FailureReasonEntry } from '../../types';

export function FailureReasonsCard({ data }: { data?: FailureReasonEntry[] }) {
  const t = useT();
  if (!data?.length) return null;

  const maxCount = Math.max(...data.map((d) => d.count));

  return (
    <Card padding={false}>
      <div className="p-5 pb-0"><CardHeader title={t.insights.failureAnalysis} /></div>
      <div className="p-5 pt-2 space-y-2">
        {data.map((d) => (
          <div key={d.reason} className="flex items-center gap-3">
            <div className="flex-1 min-w-0">
              <p className="text-xs text-text-secondary truncate" title={d.reason}>{d.reason}</p>
              <div className="h-1.5 rounded-full bg-border overflow-hidden mt-1">
                <div
                  className="h-full rounded-full bg-danger transition-all"
                  style={{ width: `${(d.count / maxCount) * 100}%` }}
                />
              </div>
            </div>
            <span className="text-xs font-medium text-text-tertiary shrink-0 tabular-nums w-6 text-right">{d.count}</span>
          </div>
        ))}
      </div>
    </Card>
  );
}
