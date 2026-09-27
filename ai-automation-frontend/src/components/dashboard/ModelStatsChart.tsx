import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell } from 'recharts';
import { useI18nStore, useT } from '../../i18n';
import { formatNumber } from '../../utils/localeFormat';
import type { ModelStats } from '../../types';
import { aggregateModelTokens } from './modelStatsData';

const BAR_COLORS = [
  'var(--color-accent)', 'var(--color-chart-1)', 'var(--color-chart-2)',
  'var(--color-chart-3)', 'var(--color-chart-4)',
];

export function ModelStatsChart({ data }: { data: ModelStats[] }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const chartData = aggregateModelTokens(data).map((entry) => ({
    ...entry, name: entry.model ?? t.charts.modelNotReported,
  }));

  return (
    <div className="max-h-80 overflow-y-auto" role="region" aria-label={t.charts.modelSimple} tabIndex={0}>
      <div style={{ height: Math.max(160, chartData.length * 28 + 34) }}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={chartData} layout="vertical" margin={{ top: 4, right: 8, left: 8, bottom: 0 }}>
            <XAxis type="number" axisLine={false} tickLine={false}
              tick={{ fill: 'var(--color-text-tertiary)', fontSize: 11 }}
              tickFormatter={(value) => `${formatNumber(Number(value) / 1000, locale, { maximumFractionDigits: 1 })}k`} />
            <YAxis type="category" dataKey="name" interval={0} width={100} axisLine={false} tickLine={false}
              tick={{ fill: 'var(--color-text-secondary)', fontSize: 11 }}
              tickFormatter={(value: string) => {
                const short = value.replace(/^claude-/, '').replace(/-\d{8,}$/, '');
                return short.length > 16 ? `${short.slice(0, 15)}…` : short;
              }} />
            <Tooltip contentStyle={{ backgroundColor: 'var(--color-surface-overlay)', border: '1px solid var(--color-border)', borderRadius: 8, color: 'var(--color-text)', fontSize: 12 }}
              formatter={(value) => [formatNumber(Number(value ?? 0), locale), t.charts.tokens]}
              labelStyle={{ color: 'var(--color-text-secondary)' }} />
            <Bar dataKey="tokens" radius={[0, 4, 4, 0]} maxBarSize={16}>
              {chartData.map((entry, index) => <Cell key={entry.model ?? ''} fill={BAR_COLORS[index % BAR_COLORS.length]} />)}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
