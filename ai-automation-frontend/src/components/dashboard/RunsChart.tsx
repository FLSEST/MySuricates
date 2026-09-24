import { useMemo } from 'react';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';
import { ChartCard } from './ChartCard';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import type { TimeSeriesPoint } from '../../types';

interface RunsChartProps {
  data?: TimeSeriesPoint[];
}

export function RunsChart({ data }: RunsChartProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);

  const chartData = useMemo(
    () =>
      data?.map((p) => ({
        date: formatDate(p.date, locale, { month: 'short', day: 'numeric' }),
        count: p.count,
      })) ?? [],
    [data, locale]
  );

  return (
    <ChartCard title={t.charts.runsOverTime} isEmpty={!data?.length}>
      <div className="h-40">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={chartData} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
            <defs>
              <linearGradient id="runsGradient" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="var(--color-accent)" stopOpacity={0.3} />
                <stop offset="95%" stopColor="var(--color-accent)" stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
            <XAxis
              dataKey="date"
              tick={{ fill: 'var(--color-text-tertiary)', fontSize: 12 }}
              axisLine={{ stroke: 'var(--color-border)' }}
              tickLine={false}
            />
            <YAxis
              tick={{ fill: 'var(--color-text-tertiary)', fontSize: 12 }}
              axisLine={false}
              tickLine={false}
              allowDecimals={false}
            />
            <Tooltip
              contentStyle={{
                backgroundColor: 'var(--color-surface-overlay)',
                border: '1px solid var(--color-border)',
                borderRadius: '8px',
                color: 'var(--color-text)',
                fontSize: 13,
              }}
              labelStyle={{ color: 'var(--color-text-secondary)' }}
            />
            <Area
              type="monotone"
              dataKey="count"
              name={t.charts.runs}
              stroke="var(--color-accent)"
              strokeWidth={2}
              fill="url(#runsGradient)"
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </ChartCard>
  );
}
