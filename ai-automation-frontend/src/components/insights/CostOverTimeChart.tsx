import { useMemo } from 'react';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { ChartCard } from '../dashboard/ChartCard';
import { useI18nStore, useT } from '../../i18n';
import { formatDate, formatUsd } from '../../utils/localeFormat';
import type { TimeSeriesPoint } from '../../types';

export function CostOverTimeChart({ data }: { data?: TimeSeriesPoint[] }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const chartData = useMemo(
    () => data?.map((p) => ({
      date: formatDate(p.date, locale, { month: 'short', day: 'numeric' }),
      cost: Number((p.totalCostUsd ?? 0).toFixed(4)),
    })) ?? [],
    [data, locale]
  );

  return (
    <ChartCard title={t.insights.costOverTime} isEmpty={!chartData.length}>
      <div className="h-48">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={chartData} margin={{ top: 8, right: 8, left: -8, bottom: 0 }}>
            <defs>
              <linearGradient id="costGradient" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="var(--color-chart-3)" stopOpacity={0.3} />
                <stop offset="95%" stopColor="var(--color-chart-3)" stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
            <XAxis dataKey="date" tick={{ fill: 'var(--color-text-tertiary)', fontSize: 11 }} axisLine={false} tickLine={false} />
            <YAxis tick={{ fill: 'var(--color-text-tertiary)', fontSize: 11 }} axisLine={false} tickLine={false} tickFormatter={(v) => formatUsd(Number(v), locale)} />
            <Tooltip
              contentStyle={{ backgroundColor: 'var(--color-surface-overlay)', border: '1px solid var(--color-border)', borderRadius: '8px', color: 'var(--color-text)', fontSize: 12 }}
              formatter={(value) => [formatUsd(Number(value ?? 0), locale, 4), t.insights.cost]}
            />
            <Area type="monotone" dataKey="cost" stroke="var(--color-chart-3)" strokeWidth={2} fill="url(#costGradient)" />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </ChartCard>
  );
}
