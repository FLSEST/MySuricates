import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell } from 'recharts';
import { ChartCard } from '../dashboard/ChartCard';
import { useT } from '../../i18n';
import type { CostByWorkflowEntry } from '../../types';

const COLORS = ['var(--color-accent)', '#3b82f6', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899', '#14b8a6', '#f97316', '#6366f1'];

function shortName(name: string): string {
  return name.length > 24 ? name.substring(0, 22) + '...' : name;
}

export function CostByWorkflowChart({ data }: { data?: CostByWorkflowEntry[] }) {
  const t = useT();
  const chartData = data?.map((d) => ({
    name: shortName(d.workflowName),
    cost: Number((d.totalCostUsd ?? 0).toFixed(4)),
    runs: d.runCount,
  })) ?? [];

  return (
    <ChartCard title={t.insights.costByWorkflow} isEmpty={!chartData.length}>
      <div className="h-48">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={chartData} layout="vertical" margin={{ top: 4, right: 8, left: 8, bottom: 0 }}>
            <XAxis type="number" tick={{ fill: 'var(--color-text-tertiary)', fontSize: 11 }} axisLine={false} tickLine={false} tickFormatter={(v) => `$${v}`} />
            <YAxis type="category" dataKey="name" tick={{ fill: 'var(--color-text-secondary)', fontSize: 10 }} axisLine={false} tickLine={false} width={100} />
            <Tooltip
              contentStyle={{ backgroundColor: 'var(--color-surface-overlay)', border: '1px solid var(--color-border)', borderRadius: '8px', color: 'var(--color-text)', fontSize: 12 }}
              formatter={(value: number) => [`$${value.toFixed(4)}`, t.insights.cost]}
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
