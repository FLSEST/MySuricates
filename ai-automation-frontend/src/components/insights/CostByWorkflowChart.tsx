import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell } from 'recharts';
import { ChartCard } from '../dashboard/ChartCard';
import { useI18nStore, useT } from '../../i18n';
import { formatUsd, usdAxisDigits } from '../../utils/localeFormat';
import type { CostByWorkflowEntry } from '../../types';

const COLORS = ['var(--color-accent)', 'var(--color-chart-1)', 'var(--color-chart-2)', 'var(--color-chart-3)', 'var(--color-chart-4)', 'var(--color-chart-5)', 'var(--color-chart-6)', 'var(--color-chart-7)', 'var(--color-chart-8)'];

function shortName(name: string): string {
  return name.length > 24 ? name.substring(0, 22) + '...' : name;
}

export function CostByWorkflowChart({ data }: { data?: CostByWorkflowEntry[] }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const chartData = data?.map((d) => ({
    name: shortName(d.workflowName),
    cost: Number((d.totalCostUsd ?? 0).toFixed(4)),
    runs: d.runCount,
  })) ?? [];
  const axisDigits = usdAxisDigits(Math.max(0, ...chartData.map((d) => d.cost)));

  return (
    <ChartCard title={t.insights.costByWorkflow} isEmpty={!chartData.length}>
      <div className="h-48">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={chartData} layout="vertical" margin={{ top: 4, right: 8, left: 8, bottom: 0 }}>
            <XAxis type="number" tick={{ fill: 'var(--color-text-tertiary)', fontSize: 11 }} axisLine={false} tickLine={false} tickFormatter={(v) => formatUsd(Number(v), locale, axisDigits)} />
            <YAxis type="category" dataKey="name" tick={{ fill: 'var(--color-text-secondary)', fontSize: 10 }} axisLine={false} tickLine={false} width={100} />
            <Tooltip
              contentStyle={{ backgroundColor: 'var(--color-surface-overlay)', border: '1px solid var(--color-border)', borderRadius: '8px', color: 'var(--color-text)', fontSize: 12 }}
              formatter={(value) => [formatUsd(Number(value ?? 0), locale, 4), t.insights.cost]}
            />
            <Bar dataKey="cost" radius={[0, 4, 4, 0]} maxBarSize={14}>
              {chartData.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    </ChartCard>
  );
}
