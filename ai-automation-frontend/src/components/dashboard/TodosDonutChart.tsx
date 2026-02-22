import { useMemo } from 'react';
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from 'recharts';
import { ChartCard } from './ChartCard';
import { useTodoCount } from '../../hooks/useTodos';
import { useT } from '../../i18n';

const COLORS = {
  open: 'var(--color-text-tertiary)',
  inProgress: 'var(--color-accent)',
  done: 'var(--color-success)',
};

export function TodosDonutChart() {
  const t = useT();
  const { data: counts } = useTodoCount();

  const chartData = useMemo(() => {
    if (!counts) return [];
    return [
      { name: t.dashboard.todoOpen, value: counts.open, color: COLORS.open },
      { name: t.dashboard.todoInProgress, value: counts.inProgress, color: COLORS.inProgress },
      { name: t.dashboard.todoDone, value: counts.done, color: COLORS.done },
    ].filter((d) => d.value > 0);
  }, [counts, t]);

  const total = (counts?.open ?? 0) + (counts?.inProgress ?? 0) + (counts?.done ?? 0);

  return (
    <ChartCard title={t.dashboard.todosOverview} isEmpty={total === 0}>
      <div className="h-40 relative">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={chartData}
              cx="50%"
              cy="50%"
              innerRadius="55%"
              outerRadius="80%"
              paddingAngle={3}
              dataKey="value"
              strokeWidth={0}
            >
              {chartData.map((entry, i) => (
                <Cell key={i} fill={entry.color} />
              ))}
            </Pie>
            <Tooltip
              contentStyle={{
                backgroundColor: 'var(--color-surface-overlay)',
                border: '1px solid var(--color-border)',
                borderRadius: '8px',
                color: 'var(--color-text)',
                fontSize: 13,
              }}
            />
          </PieChart>
        </ResponsiveContainer>
        {/* Center label */}
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
          <div className="text-center">
            <p className="text-2xl font-bold text-text">{total}</p>
            <p className="text-[10px] text-text-tertiary uppercase tracking-wide">Total</p>
          </div>
        </div>
      </div>
    </ChartCard>
  );
}
