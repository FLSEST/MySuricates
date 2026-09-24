import { useMemo } from 'react';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';
import { ChartCard } from './ChartCard';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import type { TimeSeriesPoint } from '../../types';

interface SuccessRateChartProps {
  data?: TimeSeriesPoint[];
}

export function SuccessRateChart({ data }: SuccessRateChartProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);

  const chartData = useMemo(
    () =>
      data?.map((p) => ({
        date: formatDate(p.date, locale, { month: 'short', day: 'numeric' }),
        rate: Math.round(p.successRate * 100),
      })) ?? [],
    [data, locale]
  );

  return (
    <ChartCard title={t.charts.successRate} isEmpty={!data?.length}>
      <div className="h-40">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={chartData} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
            <XAxis
              dataKey="date"
              tick={{ fill: 'var(--color-text-tertiary)', fontSize: 12 }}
              axisLine={{ stroke: 'var(--color-border)' }}
              tickLine={false}
            />
            <YAxis
              domain={[0, 100]}
              tick={{ fill: 'var(--color-text-tertiary)', fontSize: 12 }}
              axisLine={false}
              tickLine={false}
              tickFormatter={(v) => `${v}%`}
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
              formatter={(value) => [`${Number(value ?? 0)}%`, t.charts.rate]}
            />
            <Line
              type="monotone"
              dataKey="rate"
              name={t.charts.rate}
              stroke="var(--color-success)"
              strokeWidth={2}
              dot={{ fill: 'var(--color-success)', r: 3 }}
              activeDot={{ r: 5 }}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </ChartCard>
  );
}
