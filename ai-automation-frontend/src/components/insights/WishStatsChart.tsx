import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from 'recharts';
import { ChartCard } from '../dashboard/ChartCard';
import { useT } from '../../i18n';
import type { WishStats } from '../../types';

const COLORS = ['var(--color-success)', 'var(--color-danger)', '#3b82f6'];

export function WishStatsChart({ data }: { data?: WishStats }) {
  const t = useT();
  if (!data || data.total === 0) return null;

  const chartData = [
    { name: t.insights.wishCompleted, value: data.completed },
    { name: t.insights.wishFailed, value: data.failed },
    { name: t.insights.wishInProgress, value: data.inProgress },
  ].filter((d) => d.value > 0);

  return (
    <ChartCard title={t.insights.wishesSection} isEmpty={data.total === 0}>
      <div className="h-40 relative">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie data={chartData} cx="50%" cy="50%" innerRadius={40} outerRadius={60} dataKey="value" strokeWidth={0}>
              {chartData.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
            </Pie>
            <Tooltip contentStyle={{ backgroundColor: 'var(--color-surface-overlay)', border: '1px solid var(--color-border)', borderRadius: '8px', color: 'var(--color-text)', fontSize: 12 }} />
          </PieChart>
        </ResponsiveContainer>
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
          <div className="text-center">
            <p className="text-lg font-bold text-text">{data.total}</p>
            <p className="text-[10px] text-text-tertiary">{t.insights.wishes}</p>
          </div>
        </div>
      </div>
      <div className="flex justify-center gap-3 -mt-1">
        {chartData.map((d, i) => (
          <div key={d.name} className="flex items-center gap-1 text-[10px] text-text-secondary">
            <span className="w-1.5 h-1.5 rounded-full" style={{ backgroundColor: COLORS[i] }} />
            {d.name}: {d.value}
          </div>
        ))}
      </div>
    </ChartCard>
  );
}
