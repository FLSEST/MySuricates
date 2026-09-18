import { Gauge, Hourglass } from 'lucide-react';
import { useMemo } from 'react';
import { Card, CardHeader } from '../ui';
import { useT } from '../../i18n';
import type { RequestMetricRoute } from '../../types';

interface ApiLatencyCardProps {
  data?: RequestMetricRoute[];
  /** How many routes to list. */
  topN?: number;
}

/** Above this share of time spent queuing, the bottleneck is not the route itself. */
const WAIT_SHARE_WARNING = 0.5;

/** Below this, a route is fast enough that its bar would only be visual noise. */
const QUIET_P95_MS = 50;

export function ApiLatencyCard({ data, topN = 5 }: ApiLatencyCardProps) {
  const t = useT();

  const routes = useMemo(
    () => [...(data ?? [])].sort((a, b) => b.p95Ms - a.p95Ms).slice(0, topN),
    [data, topN]
  );

  /**
   * The single number that says whether to look at a route at all: if most of the time is spent
   * queuing for the one SQLite connection, the cause is whatever holds it, not this endpoint.
   */
  const waitShare = useMemo(() => {
    const totalCalls = data?.reduce((sum, r) => sum + r.calls, 0) ?? 0;
    if (!totalCalls) return 0;
    return (data ?? []).reduce((sum, r) => sum + r.waitShare * r.calls, 0) / totalCalls;
  }, [data]);

  const slowest = routes[0]?.p95Ms ?? 0;
  const waitIsHigh = waitShare >= WAIT_SHARE_WARNING;

  return (
    <Card>
      <CardHeader title={t.dashboard.apiLatency} />

      {routes.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-8 text-text-tertiary">
          <Gauge className="w-7 h-7 mb-2 opacity-40" />
          <p className="text-xs">{t.dashboard.apiLatencyEmpty}</p>
        </div>
      ) : (
        <>
          {/* Hint sits below, never beside: in the narrow widget column a side-by-side layout
              squeezes the label until it reads "Waiting f…". */}
          <div className={`mb-4 rounded-lg px-3 py-2 ${waitIsHigh ? 'bg-danger-subtle' : 'bg-surface'}`}>
            <div className="flex items-baseline gap-2">
              <Hourglass
                className={`w-4 h-4 shrink-0 self-center ${waitIsHigh ? 'text-danger' : 'text-text-secondary'}`}
              />
              <span className={`text-lg font-bold tabular-nums ${waitIsHigh ? 'text-danger' : 'text-text'}`}>
                {Math.round(waitShare * 100)}%
              </span>
              <span className="text-xs text-text-secondary">{t.dashboard.apiWaitShare}</span>
            </div>
            <p className="text-[10px] text-text-tertiary leading-snug mt-1">
              {waitIsHigh ? t.dashboard.apiWaitShareHigh : t.dashboard.apiWaitShareHint}
            </p>
          </div>

          <div className="space-y-2.5">
            {routes.map((route) => {
              const share = slowest > 0 ? route.p95Ms / slowest : 0;
              const quiet = route.p95Ms < QUIET_P95_MS;
              return (
                <div key={route.route}>
                  <div className="flex items-center justify-between gap-2 mb-1">
                    <span className="text-xs text-text truncate" title={route.route}>
                      {route.route}
                    </span>
                    <span className="text-[10px] text-text-secondary shrink-0 font-medium tabular-nums">
                      {route.p95Ms} ms
                    </span>
                  </div>
                  <div className="h-1 rounded-full bg-surface overflow-hidden">
                    <div
                      className={`h-full rounded-full transition-all duration-500 ${
                        quiet ? 'bg-success' : 'bg-accent'
                      }`}
                      style={{ width: `${Math.max(share * 100, 2)}%` }}
                    />
                  </div>
                  <p className="text-[10px] text-text-tertiary mt-0.5">
                    {t.dashboard.apiRouteDetail
                      .replace('{calls}', String(route.calls))
                      .replace('{sql}', String(route.maxSqlStatements))}
                  </p>
                </div>
              );
            })}
          </div>
        </>
      )}
    </Card>
  );
}
