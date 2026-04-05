import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from 'recharts';
import { ChartCard } from '../dashboard/ChartCard';
import { useT } from '../../i18n';
import type { TriggerDistribution } from '../../types';

const COLORS = ['var(--color-text-secondary)', '#3b82f6', '#10b981', '#8b5cf6', '#f59e0b'];

export function TriggerDistributionChart({ data }: { data?: TriggerDistribution }) {
  const t = useT();
  if (!data) return null;

  const entries = [
    { name: t.insights.triggerManual, value: data.manual },
    { name: t.insights.triggerScheduled, value: data.scheduled },
    { name: t.insights.triggerWebhook, value: data.webhook },
    { name: t.insights.triggerCompletion, value: data.completionTrigger },
    { name: t.insights.triggerTodo, value: data.todoTrigger },
  ].filter((e) => e.value > 0);
  const total = entries.reduce((s, e) => s + e.value, 0);

  return (
    <ChartCard title={t.insights.triggerDistribution} isEmpty={total === 0}>
      <div className="h-48 relative">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie data={entries} cx="50%" cy="50%" innerRadius={50} outerRadius={72} dataKey="value" strokeWidth={0}>
              {entries.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
            </Pie>
            <Tooltip
              contentStyle={{ backgroundColor: 'var(--color-surface-overlay)', border: '1px solid var(--color-border)', borderRadius: '8px', color: 'var(--color-text)', fontSize: 12 }}
            />
          </PieChart>
        </ResponsiveContainer>
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
          <div className="text-center">
            <p className="text-lg font-bold text-text">{total}</p>
            <p className="text-[10px] text-text-tertiary">{t.insights.runs}</p>
          </div>
        </div>
      </div>
      <div className="flex flex-wrap justify-center gap-3 -mt-2">
        {entries.map((e, i) => (
          <div key={e.name} className="flex items-center gap-1.5 text-xs text-text-secondary">
            <span className="w-2 h-2 rounded-full" style={{ backgroundColor: COLORS[i % COLORS.length] }} />
            {e.name}: {e.value}
          </div>
        ))}
      </div>
    </ChartCard>
  );
}
