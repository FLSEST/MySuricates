import { useMemo } from 'react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { ChartCard } from '../dashboard/ChartCard';
import { useT } from '../../i18n';
import { formatDuration } from '../../utils/formatTime';
import type { TimeSeriesPoint } from '../../types';

export function DurationOverTimeChart({ data }: { data?: TimeSeriesPoint[] }) {
  const t = useT();
  const chartData = useMemo(
    () => data?.map((p) => ({
      date: new Date(p.date).toLocaleDateString(undefined, { month: 'short', day: 'numeric' }),
      duration: Math.round((p.avgDurationMs ?? 0) / 1000),
      raw: p.avgDurationMs ?? 0,
    })) ?? [],
    [data]
  );

  return (
    <ChartCard title={t.insights.durationOverTime} isEmpty={!chartData.length}>
      <div className="h-48">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={chartData} margin={{ top: 8, right: 8, left: -8, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
            <XAxis dataKey="date" tick={{ fill: 'var(--color-text-tertiary)', fontSize: 11 }} axisLine={false} tickLine={false} />
            <YAxis tick={{ fill: 'var(--color-text-tertiary)', fontSize: 11 }} axisLine={false} tickLine={false} tickFormatter={(v) => `${v}s`} />
            <Tooltip
              contentStyle={{ backgroundColor: 'var(--color-surface-overlay)', border: '1px solid var(--color-border)', borderRadius: '8px', color: 'var(--color-text)', fontSize: 12 }}
              formatter={(value: number) => [formatDuration(value * 1000), t.insights.avgDuration]}
            />
            <Line type="monotone" dataKey="duration" stroke="var(--color-accent)" strokeWidth={2} dot={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </ChartCard>
  );
}
