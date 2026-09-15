import {
  BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell,
} from 'recharts';
import { ChartCard } from './ChartCard';
import { useT } from '../../i18n';
import type { ModelStats } from '../../types';

interface ModelStatsCardProps {
  data?: ModelStats[];
}

const BAR_COLORS = [
  'var(--color-accent)',
  'var(--color-chart-1)',
  'var(--color-chart-2)',
  'var(--color-chart-3)',
  'var(--color-chart-4)',
];

function shortModelName(model: string): string {
  // Strip "claude-" prefix, then strip trailing date suffix like "-20251001"
  return model.replace(/^claude-/, '').replace(/-\d{8,}$/, '');
}

export function ModelStatsCard({ data }: ModelStatsCardProps) {
  const t = useT();

  const chartData = data?.map((m) => ({
    name: shortModelName(m.model),
    tokens: Math.round(m.totalTokens / 1000),
    cost: m.totalCostUsd,
    steps: m.stepCount,
  })) ?? [];

  return (
    <ChartCard title={t.charts.modelStats} isEmpty={!chartData.length}>
      <div className="h-40">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={chartData} layout="vertical" margin={{ top: 4, right: 8, left: 8, bottom: 0 }}>
            <XAxis
              type="number"
              tick={{ fill: 'var(--color-text-tertiary)', fontSize: 11 }}
              axisLine={false}
              tickLine={false}
              tickFormatter={(v) => `${v}k`}
            />
            <YAxis
              type="category"
              dataKey="name"
              tick={{ fill: 'var(--color-text-secondary)', fontSize: 11 }}
              axisLine={false}
              tickLine={false}
              width={72}
            />
            <Tooltip
              contentStyle={{
                backgroundColor: 'var(--color-surface-overlay)',
                border: '1px solid var(--color-border)',
                borderRadius: '8px',
                color: 'var(--color-text)',
                fontSize: 12,
              }}
              formatter={(value: number, name: string) => {
                if (name === 'tokens') return [`${value}k`, t.charts.tokens];
                return [value, name];
              }}
              labelStyle={{ color: 'var(--color-text-secondary)' }}
            />
            <Bar dataKey="tokens" radius={[0, 4, 4, 0]} maxBarSize={16}>
              {chartData.map((_, i) => (
                <Cell key={i} fill={BAR_COLORS[i % BAR_COLORS.length]} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    </ChartCard>
  );
}
